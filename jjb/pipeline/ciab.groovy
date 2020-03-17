/* comac-in-a-box build+test
   steps taken from https://guide.opencord.org/profiles/comac/install/ciab.html */

pipeline {

  /* no label, executor is determined by JJB */
  agent {
    label "${params.buildNode}"
  }

  options {
    timeout(time: 1, unit: 'HOURS')
  }

  stages {

    stage ("Clean workspace") {
      steps {
        sh 'rm -rf *'
      }
    }

    stage ("Environment Setup") {
      steps {
        sh label: 'Download Kubespray', script: '''
          cd ${HOME}
          git clone https://github.com/kubernetes-incubator/kubespray.git -b release-2.11
          '''

        sh label: 'Create Python virtual environment for Kubespray', script: '''
          sudo apt update
          sudo apt install -y software-properties-common python-pip
          sudo pip install virtualenv
          virtualenv ${HOME}/venv/ciab --no-site-packages
          source ${HOME}/venv/ciab/bin/activate
          '''

        sh label: 'Run Kubespray', script: '''
          cd ${HOME}/kubespray
          pip install -r requirements.txt
          ansible-playbook -b -i inventory/local/hosts.ini \\
              -e "{\'override_system_hostname\' : False, \'disable_swap\' : True}" \\
              -e "{\'docker_iptables_enabled\' : True}" \\
              -e "{\'kube_network_plugin_multus\' : True, \'multus_version\' : v3.2}" \\
              -e "{\'kube_apiserver_node_port_range\' : 2000-36767}" \\
              -e "{\'kube_feature_gates\' : [SCTPSupport=True]}" \\
              -e "{\'helm_enabled\' : True}" \\
              cluster.yml
          deactivate
          '''

        sh label: 'Copy the cluster config to user home', script: '''
          mkdir -p ${HOME}/.kube
          sudo cp -f /etc/kubernetes/admin.conf ${HOME}/.kube/config
          sudo chown $(id -u):$(id -g) ${HOME}/.kube/config
          '''

        sh label: 'Init Helm and add additional Helm repositories', script: '''
          helm init --wait --client-only
          helm repo add incubator https://kubernetes-charts-incubator.storage.googleapis.com/
          helm repo add cord https://charts.opencord.org
          '''

        sh label: 'Install https and jq commands', script: '''
          sudo apt install -y jq
          '''

        sh label: 'Install additional CNI plugins', script: '''
          cd ${HOME}
          git clone https://gerrit.opencord.org/automation-tools
          sudo cp ${HOME}/automation-tools/comac-in-a-box/resources/simpleovs /opt/cni/bin/

          mkdir -p /tmp/cni-plugins
          cd /tmp/cni-plugins
          wget https://github.com/containernetworking/plugins/releases/download/v0.8.2/cni-plugins-linux-amd64-v0.8.2.tgz
          tar xvfz cni-plugins-linux-amd64-v0.8.2.tgz
          sudo cp /tmp/cni-plugins/static /opt/cni/bin/
          '''

        sh label: 'Set up OVS bridges', script: '''
          sudo apt install -y openvswitch-switch
          sudo ovs-vsctl --may-exist add-br br-s1u-net
          sudo ovs-vsctl --may-exist add-port br-s1u-net s1u-enb -- set Interface s1u-enb type=internal
          sudo ip addr add 119.0.0.4/24 dev s1u-enb
          sudo ip link set s1u-enb up
          '''

        sh label: 'Set up Quagga', script: '''
          kubectl apply -f ${HOME}/automation-tools/comac-in-a-box/resources/router.yaml
          kubectl wait pod -n default --for=condition=Ready -l app=router --timeout=300s
          kubectl -n default exec router ip route add 10.250.0.0/16 via 192.168.250.3
          kubectl delete net-attach-def sgi-net
          '''
      }
    }

    stage ("Get Helm Charts") {
      steps {
        sh label: 'Clone Repositories', script: '''
          cd ${HOME}
          mkdir -p cord; cd cord
          git clone https://gerrit.opencord.org/helm-charts
          git clone https://gerrit.opencord.org/cord-platform
          '''

        sh label: 'Fetch helm-charts Gerrit Change', script: '''
          cd helm-charts/
          git review -d ${params.GERRIT_CHANGE_NUMBER}
          '''

        sh label: 'Install CORD platform and COMAC profile', script: '''
          cd ${HOME}/cord/cord-platform
          helm dep update cord-platform
          helm upgrade --install cord-platform cord-platform --set etcd-operator.enabled=false
          cd ${HOME}/cord/helm-charts
          helm dep update comac
          helm upgrade --install comac comac --set mcord-services.fabric.enabled=false --set mcord-services.progran.enabled=false
          '''
      }
    }

    stage ("Install OMEC"){
      steps {
        sh label: 'Set Values', script: '''
          cd ${HOME}
          cat >> omec-values.yaml << EOF
          resources:
          enabled: false
          cassandra:
          config:
            cluster_size: 1
            seed_size: 1
          config:
          sriov:
            enabled: false
          spgwc:
            multiUpfs: false
          spgwu:
            multiUpfs: false
            devices: "--no-pci --vdev eth_af_packet0,iface=s1u-net --vdev eth_af_packet1,iface=sgi-net"
          hss:
            bootstrap:
              apn: apn1
              key: "465b5ce8b199b49faa5f0a2ee238a6bc"
              opc: "d4416644f6154936193433dd20a0ace0"
              sqn: 96
              users:
                - imsiStart: "208014567891201"
                  msisdnStart: "1122334455"
                  count: 10
          networks:
          cniPlugin: simpleovs
          ipam: static
          EOF
          '''

        sh label: 'Release OMEC DP & CP', script: '''
          cd ${HOME}/cord/helm-charts/omec
          helm upgrade --install omec-data-plane omec-data-plane --namespace omec -f ${HOME}/omec-values.yaml
          helm dep up omec-control-plane
          helm upgrade --install omec-control-plane omec-control-plane --namespace omec -f ${HOME}/omec-values.yaml
          '''
      }
    }

    stage ("Build OpenAirInterface UE Image"){
      steps {
        sh label: 'Build OAI UE Image', script: '''
          cd ${HOME}
          git clone https://github.com/opencord/openairinterface.git
          cd ${HOME}/openairinterface
          sudo docker build . --target lte-uesoftmodem \
              --build-arg build_base=omecproject/oai-base:1.0.0 \
              --file Dockerfile.ue \
              --tag omecproject/lte-uesoftmodem:1.0.0
          '''
      }
    }

    stage ("Install OpenAirInterface Simulator"){
      steps {
        sh label: 'Set Values', script: '''
          cd ${HOME}
          cat >> oai-values.yaml << EOF
          config:
            enb:
              mme:
                address: 127.0.0.1
              networks:
                s1u:
                  interface: s1u-enb
            plmn:
              mcc: "208"
              mnc: "01"
              mnc_length: 2
            ue:
              sim:
                msin: "4567891201"
                api_key: "465b5ce8b199b49faa5f0a2ee238a6bc"
                opc: "d4416644f6154936193433dd20a0ace0"
                msisdn: "1122334456"
                sqn: "96"
          EOF
          '''

        sh label: 'Install OpenAirInterface eNB and UE', script: '''
          sudo ip addr add 127.0.0.2/8 dev lo
          mme_iface=$(ip -4 route list default | awk -F 'dev' '{ print $2; exit }' | awk '{ print $1 }')

          cd ${HOME}/cord/helm-charts
          helm upgrade --install --namespace omec oaisim oaisim -f ${HOME}/oai-values.yaml --set config.enb.networks.s1_mme.interface=${mme_iface}
          '''
      }
    }

    stage ("Validate Installation"){
      steps {
        sh label: 'Attach UE to Network', script: '''
          ip addr show oip1
          '''

        sh label: 'Ping Emulated Upstream Router from UE', script: '''
          echo "Test1: ping from UE to SGI network gateway"
          ping -I oip1 192.168.250.250 -c 3

          echo "Test2: ping from UE to 8.8.8.8"
          ping -I oip1 8.8.8.8 -c 3

          echo "Test3: ping from UE to opennetworking.org and google.com"
          ping -I oip1 opennetworking.org -c 3
          ping -I oip1 google.com -c 3
          '''
      }
    }
  }
}
