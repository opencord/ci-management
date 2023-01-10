#!/bin/bash
# Ubuntu base build

# vim: ts=4 sw=4 sts=4 et tw=72 :

# force any errors to cause the script and job to end in failure
set -xeu -o pipefail

rh_systems() {
    echo 'No changes to apply'
}

ubuntu_systems() {
    DISTRO=$(lsb_release -cs)

    # get prereqs for PPA and apt-over-HTTPS support
    apt-get clean
    apt-get update
    apt-get install -y apt-transport-https software-properties-common

    # set up git backports repo
    add-apt-repository -y  ppa:git-core/ppa

    # set up docker repo
    cat << EOF | base64 -d > /tmp/docker-apt-key.gpg
LS0tLS1CRUdJTiBQR1AgUFVCTElDIEtFWSBCTE9DSy0tLS0tCgptUUlOQkZpdDJpb0JFQU
RoV3BaOC93dlo2aFVUaVhPd1FIWE1BbGFGSGNQSDloQXRyNEYxeTIrT1lkYnRNdXRoCmxx
cXdwMDI4QXF5WStQUmZWTXRTWU1ianVRdXU1Ynl5S1IwMUJicVlodVMzanRxUW1salovYk
p2WHFubWlWWGgKMzhVdUxhK3owNzdQeHl4UWh1NUJicW50VFBRTWZpeXFFaVUrQkticTJX
bUFOVUtRZisxQW1aWS9JcnVPWGJucQpMNEMxK2dKOHZmbVhRdDk5bnBDYXhFamFOUlZZZk
9TOFFjaXhOekhVWW5iNmVtamxBTnlFVmxaemVxbzdYS2w3ClVyd1Y1aW5hd1RTeldOdnRq
RWpqNG5KTDhOc0x3c2NwTFBRVWhUUSs3QmJRWEF3QW1lSENVVFFJdnZXWHF3ME4KY21oaD
RIZ2VRc2NRSFlnT0pqakRWZm9ZNU11Y3ZnbGJJZ0NxZnpBSFc5anhtUkw0cWJNWmorYjFY
b2VQRXRodAprdTRiSVFOMVg1UDA3Zk5XemxnYVJMNVo0UE9YRERaVGxJUS9FbDU4ajlrcD
RibldSQ0pXMGx5YStmOG9jb2RvCnZaWitEb2krZnk0RDVaR3JMNFhFY0lRUC9MdjV1Rnlm
K2tRdGwvOTRWRllWSk9sZUF2OFc5MktkZ0RraFRjVEQKRzdjMHRJa1ZFS05VcTQ4YjNhUT
Y0Tk9aUVc3ZlZqZm9Ld0VaZE9xUEU3MlBhNDVqclp6dlVGeFNwZGlOazJ0WgpYWXVrSGps
eHhFZ0JkQy9KM2NNTU5SRTFGNE5DQTNBcGZWMVk3L2hUZU9ubUR1RFl3cjkvb2JBOHQwMT
ZZbGpqCnE1cmRreXdQZjRKRjhtWFVXNWVDTjF2QUZIeGVnOVpXZW1oQnRRbUd4WG53OU0r
ejZoV3djNmFobXdBUkFRQUIKdEN0RWIyTnJaWElnVW1Wc1pXRnpaU0FvUTBVZ1pHVmlLU0
E4Wkc5amEyVnlRR1J2WTJ0bGNpNWpiMjAraVFJMwpCQk1CQ2dBaEJRSllyZWZBQWhzdkJR
c0pDQWNEQlJVS0NRZ0xCUllDQXdFQUFoNEJBaGVBQUFvSkVJMkJnRHdPCnY4Mklzc2tQL2
lRWm82OGZsRFFtTnZuOFg1WFRkNlJSYVVIMzNrWFlYcXVUNk5rSEpjaVM3RTJnVEptcXZN
cWQKdEk0bU5ZSENTRVl4STVxcmNZVjVZcVg5UDYrS28rdm96bzRuc2VVUUxQSC9BVFE0cU
wwWm9rKzFqa2FnM0xnawpqb255VWY5Ynd0V3hGcDA1SEMzR01IUGhoY1VTZXhDeFFMUXZu
RldYRDJzV0xLaXZIcDJmVDhRYlJHZVorZDNtCjZmcWNkNUZ1N3B4c3FtMEVVREs1Tkwrbl
BJZ1loTithdVRyaGd6aEsxQ1NoZkdjY00vd2ZSbGVpOVV0ejZwOVAKWFJLSWxXblh0VDRx
TkdaTlROMHRSK05MRy82QnFkOE9ZQmFGQVVjdWUvdzFWVzZKUTJWR1laSG5adTlTOExNYw
pGWUJhNUlnOVB4d0dRT2dxNlJES0RiVitQcVRRVDVFRk1lUjFtcmpja2s0RFFKamJ4ZU1a
YmlOTUc1a0dFQ0E4CmczODNQM2VsaG4wM1dHYkVFYTRNTmMzWjQrN2MyMzZRSTN4V0pmTl
BkVWJYUmFBd2h5LzZyVFNGYnp3S0IwSm0KZWJ3elFmd2pRWTZmNTVNaUkvUnFEQ3l1UGoz
cjNqeVZSa0s4NnBRS0JBSndGSHlxajlLYUtYTVpqZlZub3dMaAo5c3ZJR2ZOYkdIcHVjQV
RxUkV2VUh1UWJObnFrQ3g4VlZodFlraERiOWZFUDJ4QnU1VnZIYlIrM25mVmhNdXQ1Ckcz
NEN0NVJTN0p0NkxJZkZkdGNuOENhU2FzL2wxSGJpR2VSZ2M3MFgvOWFZeC9WL0NFSnYwbE
llOGdQNnVEb1cKRlBJWjdkNnZIK1ZybzZ4dVdFR2l1TWFpem5hcDJLaFptcGtnZnVweUZt
cGxoMHM2a255bXVRSU5CRml0MmlvQgpFQURuZUw5UzltNHZoVTNibGFSalZVVXlKN2IvcV
RqY1N5bHZDSDVYVUU2UjJrK2NrRVpqZkFNWlBMcE8rL3RGCk0ySklKTUQ0U2lmS3VTM3hj
azlLdFpHQ3VmR21jd2lMUVJ6ZUhGN3ZKVUtyTEQ1UlRrTmkyM3lkdldaZ1BqdHgKUStEVF
QxWmNuN0JyUUZZNkZnblJvVVZJeHd0ZHcxYk1ZLzg5cnNGZ1M1d3d1TUVTZDNRMlJZZ2I3
RU9GT3BudQp3NmRhN1dha1dmNElobkY1bnNOWUdEVmFJSHpwaXFDbCt1VGJmMWVwQ2pyT2
xJemtaM1ozWWs1Q00vVGlGelBrCnoybEx6ODljcEQ4VStOdENzZmFnV1dmamQyVTNqRGFw
Z0grN25RbkNFV3BST3R6YUtIRzZsQTNwWGRpeDV6RzgKZVJjNi8wSWJVU1d2ZmpLeExMUG
ZOZUNTMnBDTDNJZUVJNW5vdGhFRVlkUUg2c3pwTG9nNzl4QjlkVm5KeUtKYgpWZnhYbnNl
b1lxVnJSejJWVmJVSTVCbHdtNkI0MEUzZUdWZlVRV2l1eDU0RHNweVZNTWs0MU14N1FKM2
l5bklhCjFONFpBcVZNQUVydXlYVFJUeGM5WFcwdFloRE1BLzFHWXZ6MEVtRnBtOEx6VEhB
NnNGVnRQbS9abE5DWDZQMVgKekp3cnY3RFNRS0Q2R0dsQlFVWCtPZUVKOHRUa2tmOFFUSl
NQVWRoOFA4WXhERlM1RU9HQXZoaHBNQllENDJrUQpwcVhqRUMrWGN5Y1R2R0k3aW1wZ3Y5
UERZMVJDQzF6a0JqS1BhMTIwck5odi9oa1ZrL1lodUdvYWpvSHl5NGg3ClpRb3BkY010cE
4yZGdtaEVlZ255OUpDU3d4ZlFtUTB6SzBnN202U0hpS013andBUkFRQUJpUVErQkJnQkNB
QUoKQlFKWXJkb3FBaHNDQWlrSkVJMkJnRHdPdjgySXdWMGdCQmtCQ0FBR0JRSllyZG9xQU
FvSkVINmdxY1B5Yy96WQoxV0FQLzJ3SitSMGdFNnFzY2UzcmphSXo1OFBKbWM4Z29Lcmly
NWhuRWxXaFBnYnE3Y1lJc1c1cWlGeUxoa2RwClljTW1oRDltUmlQcFFuNllhMnczZTNCOH
pmSVZLaXBiTUJua2UveXRaOU03cUhtRENjam9pU213RVhOM3dLWUkKbUQ5VkhPTnNsL0NH
MXJVOUlzdzFqdEI1ZzFZeHVCQTdNL20zNlhONngydStOdE5NREI5UDU2eWM0Z2ZzWlZFUw
pLQTl2K3lZMi9sNDVMOGQvV1VrVWkwWVhvbW42aHlCR0k3SnJCTHEwQ1gzN0dFWVA2Tzly
cktpcGZ6NzNYZk83CkpJR3pPS1psbGpiL0Q5UlgvZzduUmJDbiszRXRIN3huaytUSy81MG
V1RUt3OFNNVWcxNDdzSlRjcFFtdjZVeloKY000SmdMMEhiSFZDb2pWNEMvcGxFTHdNZGRB
TE9GZVlRelRpZjZzTVJQZiszRFNqOGZyYkluakNoQzN5T0x5MAo2YnI5MktGb20xN0VJaj
JDQWNvZXE3VVBoaTJvb3VZQndQeGg1eXRkZWhKa29vK3NON1JJV3VhNlAyV1Ntb241ClU4
ODhjU3lsWEMwK0FERmRnTFg5SzJ6ckRWWVVHMXZvOENYMHZ6eEZCYUh3TjZQeDI2ZmhJVD
EvaFlVSFFSMXoKVmZORGN5UW1YcWtPblp2dm9NZnovUTBzOUJoRkovelU2QWdRYklaRS9o
bTFzcHNmZ3Z0c0QxZnJaZnlnWEo5ZgppclArTVNBSTgweEhTZjkxcVNSWk9qNFBsM1pKTm
JxNHlZeHYwYjFwa01xZUdkamRDWWhMVStMWjR3YlFtcENrClNWZTJwcmxMdXJlaWdYdG1a
ZmtxZXZSejdGcklaaXU5a3k4d25DQVB3Qzcvem1TMThyZ1AvMTdiT3RMNC9pSXoKUWh4QU
FvQU1XVnJHeUppdlNramhTR3gxdUNvanNXZnNUQW0xMVA3anNydUlMNjFaek1VVkUyYU0z
UG1qNUcrVwo5QWNaNThFbSsxV3NWbkFYZFVSLy9iTW1oeXI4d0wvRzFZTzFWM0pFSlRSZH
hzU3hkWWE0ZGVHQkJZL0FkcHN3CjI0anhoT0pSK2xzSnBxSVVlYjk5OStSOGV1RGhSSEc5
ZUZPN0RSdTZ3ZWF0VUo2c3V1cG9EVFJXdHIvNHlHcWUKZEt4VjNxUWhOTFNuYUF6cVcvMW
5BM2lVQjRrN2tDYUtaeGhkaERiQ2xmOVAzN3FhUlc0NjdCTENWTy9jb0wzeQpWbTUwZHdk
ck50S3BNQmgzWnBiQjF1SnZnaTltWHR5Qk9NSjN2OFJaZUR6RmlHOEhkQ3RnOVJ2SXQvQU
lGb0hSCkgzUytVNzlOVDZpMEtQekxJbURmczhUN1JscHl1TWM0VWZzOGdneWc5djNBZTZj
TjNlUXl4Y0szdzBjYkJ3c2gKL25RTmZzQTZ1dSs5SDdOaGJlaEJNaFlucE5aeXJIekNten
lYa2F1d1JBcW9DYkdDTnlrVFJ3c3VyOWdTNDFUUQpNOHNzRDFqRmhlT0pmM2hPRG5rS1Ur
SEtqdk1ST2wxREs3emRtTGROekExY3Z0WkgvbkNDOUtQajF6OFFDNDdTCnh4K2RUWlN4NE
9OQWh3YlMvTE4zUG9LdG44TFBqWTlOUDl1RFdJK1RXWXF1UzJVK0tIRHJCRGxzZ296RGJz
L08KakN4Y3BEek5tWHBXUUhFdEhVNzY0OU9YSFA3VWVOU1QxbUNVQ0g1cWRhbmswVjFpZW
pGNi9DZlRGVTRNZmNyRwpZVDkwcUZGOTNNM3YwMUJieFArRUlZMi85dGlJUGJyZAo9MFlZ
aAotLS0tLUVORCBQR1AgUFVCTElDIEtFWSBCTE9DSy0tLS0tCg==
EOF

    apt-key add /tmp/docker-apt-key.gpg
    add-apt-repository -y \
        "deb [arch=amd64] https://download.docker.com/linux/ubuntu $DISTRO stable"

    # set up golang repo
    # docs: https://github.com/golang/go/wiki/Ubuntu
    add-apt-repository -y ppa:longsleep/golang-backports

    # set up kubernetes repo
    cat << EOF | base64 -d > /tmp/k8s-apt-key.gpg
xsBNBFrBaNsBCADrF18KCbsZlo4NjAvVecTBCnp6WcBQJ5oSh7+E98jX9YznUCrNrgmeCc
CMUvTDRDxfTaDJybaHugfba43nqhkbNpJ47YXsIa+YL6eEE9emSmQtjrSWIiY+2YJYwsDg
sgckF3duqkb02OdBQlh6IbHPoXB6H//b1PgZYsomB+841XW1LSJPYlYbIrWfwDfQvtkFQI
90r6NknVTQlpqQh5GLNWNYqRNrGQPmsB+NrUYrkl1nUt1LRGu+rCe4bSaSmNbwKMQKkROE
4kTiB72DPk7zH4Lm0uo0YFFWG4qsMIuqEihJ/9KNX8GYBr+tWgyLooLlsdK3l+4dVqd8cj
kJM1ExABEBAAHNQEdvb2dsZSBDbG91ZCBQYWNrYWdlcyBBdXRvbWF0aWMgU2lnbmluZyBL
ZXkgPGdjLXRlYW1AZ29vZ2xlLmNvbT7CwHgEEwECACwFAlrBaNsJEGoDCyG6B/T7AhsPBQ
kFo5qABgsJCAcDAgYVCAIJCgsEFgIDAQAAJr4IAM5lgJ2CTkTRu2iw+tFwb90viLR6W0N1
CiSPUwi1gjEKMr5r0aimBi6FXiHTuX7RIldSNynkypkZrNAmTMM8SU+sri7R68CFTpSgAv
W8qlnlv2iwrEApd/UxxzjYaq8ANcpWAOpDsHeDGYLCEmXOhu8LmmpY4QqBuOCM40kuTDRd
52PCJE6b0V1t5zUqdKeKZCPQPhsS/9rdYP9yEEGdsx0V/Vt3C8hjv4Uwgl8Fa3s/4ag6lg
If+4SlkBAdfl/MTuXu/aOhAWQih444igB+rvFaDYIhYosVhCxP4EUAfGZk+qfo2mCY3w1p
te31My+vVNceEZSUpMetSfwit3QA8EE=
EOF

    apt-key add /tmp/k8s-apt-key.gpg
    add-apt-repository -y "deb http://apt.kubernetes.io/ kubernetes-xenial main"

    # set up NodeJS repo
    # Instructions: https://github.com/nodesource/distributions/blob/master/README.md#manual-installation
    cat << EOF | base64 -d > /tmp/nodejs-apt-key.gpg
LS0tLS1CRUdJTiBQR1AgUFVCTElDIEtFWSBCTE9DSy0tLS0tClZlcnNpb246IEdudVBHIH
YxCkNvbW1lbnQ6IEdQR1Rvb2xzIC0gaHR0cHM6Ly9ncGd0b29scy5vcmcKCm1RSU5CRk9i
SkxZQkVBRGtGVzhITWpzb1lSSlE0bkNZQy82RWgweUxXSFdmQ2grLzlaU0lqNHcvcE9lMl
Y2VisKVzZESFkza0szYSsyYnhyYXg5RXFLZTd1eGtTS2Y5NWdmbnMrSTkrUitSSmZScGIx
cXZsalVScjU0eTM1SVpncwpmTUcyMk5wK1RtTTJSTGdkRkNaYTE4aDArUmJIOWkwYitack
I5WFBabUxiL2g5b3U3U293R3FRM3d3T3RUM1Z5CnFtaWYwQTJHQ2NqRlRxV1c2VFhhWThl
Wko5QkNFcVczay8wQ2p3N0svbVN5L3V0eFlpVUl2Wk5LZ2FHL1A4VTcKODlReXZ4ZVJ4QW
Y5M1lGQVZ6TVhob0t4dTEySXVINFZuU3dBZmI4Z1F5eEtSeWlHT1V3azBZb0JQcHFSbk1t
RApEbDdTZG1ZM29RSEVKekJlbFRNalRNOEFqYkI5bVdvUEJYNUc4dDR1NDcvRlo2UGdkZm
1SZzloc0tYaGtMSmM3CkMxYnRibE9ITmdEeDE5ZnpBU1dYK3hPalppS3BQNk1rRUV6cTFi
aWxVRnVsNlJEdHhrVFdzVGE1VEdpeGdDQi8KRzJmSzhJOUpML3lRaERjNk9HWTltalBPeE
1iNVBnVWxUOG94M3Y4d3QyNWVyV2o5ejMwUW9FQndmU2c0dHpMYwpKcTZOL2llcFFlbU5m
bzZJcytURytKekk2dmhYamxzQm0vWG16MFppRlBQT2JBSC92R0NZNUk2ODg2dlhRN2Z0Cn
FXSFlIVDhqei9SNHRpZ01HQyt0dlova2NtWUJzTENDSTV1U0VQNkpKUlFRaEhyQ3ZPWDBV
YXl0SXRmc1FmTG0KRVlSZDJGNzJvMXlHaDN5dldXZkRJQlhSbWFCdUlHWEdwYWpDMEp5Qk
dTT1diOVV4TU5aWS8yTEpFd0FSQVFBQgp0QjlPYjJSbFUyOTFjbU5sSUR4bmNHZEFibTlr
WlhOdmRYSmpaUzVqYjIwK2lRSTRCQk1CQWdBaUJRSlRteVMyCkFoc0RCZ3NKQ0FjREFnWV
ZDQUlKQ2dzRUZnSURBUUllQVFJWGdBQUtDUkFXVmFDcmFGZGlnSFRtRC85T0toVXkKakor
aDhnTVJnNnJpNUVReE9FeGNjU1JVMGk3VUhrdGVjU3MwRFZDNGxaRzlBT3pCZStRMzZjeW
01WjFkaTZKUQprSGw2OXEzekJkVjNLVFcrSDFwZG1uWmxlYllHejhwYUc5aVEvd1M5Z3Bu
U2VFeXgwRW55aTE2N0J6bTBPNEExCkdLMHBya0xuei95Uk9ISEVmSGpzVGdNdkZ3QW5mOX
VheHdXZ0UxZDFSaXRJV2dKcEFucDFEWjVPMHVWbHNQUG0KWEFodUJKMzJtVThTNUJlelBU
dUpKSUN3QmxMWUVDR2IxWTY1Q2lsNE9BTFU3VDdzYlVxZkxDdWFSS3h1UHRjVQpWbko2L3
FpeVB5Z3ZLWldoVjZPZDBZeGx5ZWQxa2Z0TUp5WW9MOGtQSGZlSEordkl5dDBzN2Nyb3Bm
aXdYb2thCjFpSkI1bkt5dC9lcU1uUFE5YVJwcWttOUFCUy9yN0FhdU1BLzlSQUx1ZFFSSE
JkV0l6ZklnME1scWI1Mnl5VEkKSWdRSkhOR05YMVQzejFYZ1poSStWaThTTEZGU2g4eDlG
ZVVaQzZZSnUwVlhYajVpeitlWm1rL25ZalV0NE10YwpwVnNWWUlCN29JREliSW1PRG04Z2
dzZ3JJenF4T3pRVlAxenNDR2VrNVU2UUZjOUdZclErV3YzL2ZHOGhma0RuCnhYTHd3ME9H
YUVReGZvZG04Y0xGWjViOEphRzMrWXhmZTdKa05jbHd2UmltdmxBanFJaVc1T0swdnZmSG
NvK1kKZ0FOaFFybE1uVHgvL0lkWnNzYXh2WXl0U0hwUFpUWXcrcVBFamJCSk9McG9Mcno4
WmFmTjF1ZWtwQXFRamZmSQpBT3FXOVNkSXpxL2tTSGdsMGJ6V2JQSlB3ODZYenpmdGV3ak
tOYmtDRFFSVG15UzJBUkFBeFNTZFFpK1dwUFFaCmZPZmxreDlzWUphMGNXekxsMncrK0ZR
bloxUG41RjA5RC9rUE1OaDRxT3N5dlhXbGVrYVYvU3NlRFp0VnppSEoKS202VjhUQkczZm
xtRmxDM0RXUWZOTkZ3bjUrcFdTQjhXSEc0YlRBNVJ5WUVFWWZwYmVrTXRkb1dXL1JvOEtt
aAo0MW51eFpEU3VCSmhEZUZJcDBjY25OMkxwMW82WGZJZURZUGVneUVQU1NacXJ1ZGZxTH
JTWmhTdERsSmdYamVhCkpqVzZVUDZ0eFB0WWFhaWxhOS9IbjZ2Rjg3QVE1YlIyZEVXQi94
Ukp6Z053UmlheDdLU1UweGNhNnhBdWYrVEQKeENqWjVwcDJKd2RDanF1WExUbVVuYklaOU
xHVjU0VVovTWVpRzh5VnU2cHhiaUduWG80RWtiazZ4Z2kxZXdMaQp2R216NFFSZlZrbFYw
ZGJhM1pqMGZSb3pmWjIycVVIeENmRE03YWQwZUJYTUZtSGlOOGhnM0lVSFRPK1VkbFgvCm
FIM2dBREZBdlNWRHYwdjh0NmRHYzZYRTlEcjdtR0VGblFNSE80emhNMUhhUzJOaDBUaUwy
dEZMdHRMYmZHNW8KUWx4Q2ZYWDkvbmFzajNLOXFubEVnOUczKzRUN2xwZFBtWlJSZTFPOG
NIQ0k1aW1WZzZjTElpQkxQTzE2ZTBmSwp5SElnWXN3TGRySkZmYUhOWU0vU1dKeEhwWDc5
NXpuK2lDd3l2WlNsTGZIOW1sZWdPZVZtajljeWhOL1ZPbVMzClFSaGxZWG9BMno3V1pUTm
9DNmlBSWx5SXBNVGNacitudGFHVnRGT0xTNmZ3ZEJxRFhqbVNRdTY2bURLd1U1RWsKZk5s
YnlycHpaTXlGQ0RXRVlvNEFJUi8xOGFHWkJZVUFFUUVBQVlrQ0h3UVlBUUlBQ1FVQ1U1c2
t0Z0liREFBSwpDUkFXVmFDcmFGZGlnSVBRRUFDY1loOHJSMTl3TVpaL2hnWXY1c282WTFI
Y0pOQVJ1em1mZlFLb3pTL3J4cWVjCjB4TTN3Y2VMMUFJTXVHaGxYRmVHZDB3UnYvUlZ6ZV
pqblRHd2hOMURuQ0R5MUk2NmhVVGdlaE9Oc2ZWYW51UDEKUFpLb0wzOEVBeHNNemRZZ2tZ
SDZUOWE0d0pIL0lQdCt1dUZURkZ5M284VEtNdkthSms5OCtKc3AyWC9RdU54aApxcGNJR2
FWYnRRMWJuN20razVRZS9meitiRnVVZVhQaXZhZkxMbEdjNktiZGdNdlNXOUVWTU83eUJ5
LzJKRTE1ClpKZ2w3bFhLTFEzMVZRUEFIVDNhbjVJVjJDL2llMTJlRXFaV2xuQ2lIVi93VC
t6aE9rU3BXZHJoZVdmQlQrYWMKaFI0akRIODBBUzNGOGpvM2J5UUFUSmIzUm9DWVVDVmMz
dTFvdWhOWmE1eUxnWVovaVprcGs1Z0tqeEhQdWRGYgpEZFdqYkdmbE45azE3VkNmNFo5eU
FiOVFNcUh6SHdJR1hyYjdyeUZjdVJPTUNMTFZVcDA3UHJUclJ4bk85QS80Cnh4RUNpMGwv
QnpOeGVVMWdLODhoRWFOaklmdmlQUi9oNkdxNktPY05LWjhyVkZkd0ZwamJ2d0hNUUJXaH
JxZnUKRzNLYWVQdmJuT2JLSFhwZklLb0FNN1gycWZPK0lGbkxHVFB5aEZUY3JsNnZaQlRN
WlRmWmlDMVhEUUx1R1VuZApzY2t1WElOSVUzREZXelpHcjBRcnFrdUUvanlyN0ZYZVVKaj
lCN2NMbytzL1RYbytSYVZmaTNrT2M5Qm94SXZ5Ci9xaU5Hcy9US3kyL1VqcXAvYWZmbUlN
b01YU296S21nYTgxSlN3a0FETzFKTWdVeTZkQXBYejlrUDRFRTNnPT0KPUNMR0YKLS0tLS
1FTkQgUEdQIFBVQkxJQyBLRVkgQkxPQ0stLS0tLQo=
EOF

    apt-key add /tmp/nodejs-apt-key.gpg
    NODE_VERSION=node_7.x
    add-apt-repository -y "deb [arch=amd64] https://deb.nodesource.com/$NODE_VERSION $DISTRO main"

    # update after adding apt repos to sources
    apt-get update

    # install basic sofware requirements
    apt-get install -y \
        "docker-ce=5:19.03*" \
        apt-transport-https \
        build-essential \
        bzip2 \
        cloud-init \
        curl \
        ebtables \
        enchant \
        ethtool \
        git \
        golang-1.12-go \
        graphviz \
        jq \
        kafkacat \
        "kubeadm=1.18*" \
        "kubelet=1.18*" \
        "kubectl=1.18*" \
        "kubernetes-cni=0.8.7*" \
        less \
        libmysqlclient-dev \
        libpcap-dev \
        libxml2-utils \
        maven \
        nodejs \
        python \
        python-dev \
        python-pip \
        python3-dev \
        python3-pip \
        ruby \
        screen \
        socat \
        ssh \
        sshpass \
        zip
        # end of apt-get install list

    # remove apt installed incompatible python tools
    # NOTE: Python3 versions are not removed, as cloud-init depends on them
    apt-get -y remove \
      python-enum34 \
      python-cryptography \
      python-openssl \
      python-ndg-httpsclient \
      python-requests \
      python-six \
      python-urllib3

    # install python3 modules
    # upgrade pip or other installations may fail in unusual ways
    pip3 install --upgrade pip
    pip3 install \
        ansible \
        ansible-lint \
        docker \
        docker-compose \
        git-review \
        httpie \
        netaddr \
        pylint \
        tox \
        twine \
        virtualenv \
        yamllint
        # end of pip3 install list

    # install python2 modules
    # upgrade pip or other installations may fail in unusual ways
    python -m pip install --upgrade pip
    python -m pip install \
        Jinja2 \
        coverage \
        certifi \
        cryptography \
        git+https://github.com/linkchecker/linkchecker.git@v9.4.0 \
        graphviz \
        grpcio-tools \
        isort \
        more-itertools==5.0.0 \
        mock==2.0.* \
        ndg-httpsclient \
        nose2==0.9.* \
        pyopenssl \
        pexpect \
        pyyaml==3.10.* \
        requests==2.14.* \
        robotframework \
        robotframework-httplibrary \
        robotframework-kafkalibrary \
        robotframework-lint \
        robotframework-requests \
        robotframework-sshlibrary \
        six \
        urllib3
        # end of pip install list

    # install ruby gems
    gem install \
        mdl -v 0.5.0
        # end of gem install list

    # install npm modules
    npm install -g \
        gitbook-cli \
        markdownlint \
        typings

    # install golang packages in /usr/local/go
    # Set PATH=$PATH:/usr/local/go/bin` to use these
    export GOPATH=/usr/local/go
    mkdir -p $GOPATH
    export PATH=$PATH:/usr/lib/go-1.12/bin:$GOPATH/bin

    # converters for unit/coverage tests
    go get -v github.com/t-yuki/gocover-cobertura
    go get -v github.com/jstemmer/go-junit-report

    # github-release - uploader for github artifacts
    go get -v github.com/github-release/github-release

    # dep for go package dependencies w/versioning, version 0.5.2, adapted from:
    #  https://golang.github.io/dep/docs/installation.html#install-from-source
    go get -d -u github.com/golang/dep
    pushd "$(go env GOPATH)/src/github.com/golang/dep"
      git checkout "0.5.2"
      go install -ldflags="-X main.version=0.5.2" ./cmd/dep
    popd

    # golangci-lint for testing
    #  https://github.com/golangci/golangci-lint#local-installation
    GO111MODULE=on go get github.com/golangci/golangci-lint/cmd/golangci-lint@v1.17.1

    # protoc-gen-go - Golang protbuf compiler extension for protoc (installed
    # below)
    go get -d -u github.com/golang/protobuf/protoc-gen-go
    pushd "$(go env GOPATH)/src/github.com/golang/protobuf"
      git checkout "v1.3.1"
      go install ./protoc-gen-go
    popd

    # install repo launcher v2.9
    REPO_B64_SHA256SUM="da4a14be94382f7ecdb22fef4f554eb0ffcf09a0d8c352667beae4a1794ad666"
    curl -o /tmp/repo.b64 'https://gerrit.googlesource.com/git-repo/+/refs/tags/v2.9/repo?format=TEXT'
    echo "$REPO_B64_SHA256SUM  /tmp/repo.b64" | sha256sum -c -
    base64 --decode /tmp/repo.b64 > /tmp/repo
    mv /tmp/repo /usr/local/bin/repo
    chmod a+x /usr/local/bin/repo

    # install helm
    HELM_VERSION="3.4.2"
    HELM_SHA256SUM="cacde7768420dd41111a4630e047c231afa01f67e49cc0c6429563e024da4b98"
    HELM_PLATFORM="linux-amd64"
    curl -L -o /tmp/helm.tgz "https://get.helm.sh/helm-v${HELM_VERSION}-${HELM_PLATFORM}.tar.gz"
    echo "$HELM_SHA256SUM  /tmp/helm.tgz" | sha256sum -c -
    pushd /tmp
    tar -xzvf helm.tgz
    mv ${HELM_PLATFORM}/helm /usr/local/bin/helm
    chmod a+x /usr/local/bin/helm
    rm -rf helm.tgz ${HELM_PLATFORM}
    popd

    # install minikube
    MINIKUBE_VERSION="1.13.1"
    MINIKUBE_SHA256SUM="ac6cd65568f1fdab13207aaed3903037b07bd660a7d0eb4331a2a4198890de39"
    curl -L -o /tmp/minikube.deb "https://github.com/kubernetes/minikube/releases/download/v${MINIKUBE_VERSION}/minikube_${MINIKUBE_VERSION}-0_amd64.deb"
    echo "$MINIKUBE_SHA256SUM  /tmp/minikube.deb" | sha256sum -c -
    pushd /tmp
    dpkg -i minikube.deb
    rm -f minikube.deb
    popd

    # install protobufs
    PROTOC_VERSION="3.7.0"
    PROTOC_SHA256SUM="a1b8ed22d6dc53c5b8680a6f1760a305b33ef471bece482e92728f00ba2a2969"
    curl -L -o /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip https://github.com/google/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip
    echo "$PROTOC_SHA256SUM  /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip" | sha256sum -c -
    unzip /tmp/protoc-${PROTOC_VERSION}-linux-x86_64.zip -d /tmp/protoc3
    mv /tmp/protoc3/bin/* /usr/local/bin/
    mv /tmp/protoc3/include/* /usr/local/include/
    # fix permissions on files
    chmod -R a+rx /usr/local/bin/*
    chmod -R a+rX /usr/local/include/

    # give sudo permissions on minikube and protoc to jenkins user
    cat << EOF > /etc/sudoers.d/88-jenkins-minikube-protoc
Cmnd_Alias CMDS = /usr/local/bin/protoc, /usr/bin/minikube
Defaults:jenkins !requiretty
jenkins ALL=(ALL) NOPASSWD:SETENV: CMDS
EOF

    # install hadolint (Dockerfile checker)
    HADOLINT_VERSION="1.18.0"
    HADOLINT_SHA256SUM="f9bc9de12438b463ca84e77fde70b07b155d4da07ca21bc3f4354a62c6199db4"
    curl -L -o /tmp/hadolint https://github.com/hadolint/hadolint/releases/download/v${HADOLINT_VERSION}/hadolint-Linux-x86_64
    echo "$HADOLINT_SHA256SUM  /tmp/hadolint" | sha256sum -c -
    mv /tmp/hadolint /usr/local/bin/hadolint
    chmod -R a+rx /usr/local/bin/hadolint

    # install pandoc (document converter)
    PANDOC_VERSION="2.10.1"
    PANDOC_SHA256SUM="4515d6fe2bf8b82765d8dfa1e1b63ccb0ff3332d60389f948672eaa37932e936"
    curl -L -o /tmp/pandoc.deb "https://github.com/jgm/pandoc/releases/download/${PANDOC_VERSION}/pandoc-${PANDOC_VERSION}-1-amd64.deb"
    echo "$PANDOC_SHA256SUM  /tmp/pandoc.deb" | sha256sum -c -
    dpkg -i /tmp/pandoc.deb
    rm -f /tmp/pandoc.deb

    # install yq (YAML query)
    YQ_VERSION="3.4.0"
    YQ_SHA256SUM="f6bd1536a743ab170b35c94ed4c7c4479763356bd543af5d391122f4af852460"
    curl -L -o /tmp/yq https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64
    echo "$YQ_SHA256SUM  /tmp/yq" | sha256sum -c -
    mv /tmp/yq /usr/local/bin/yq
    chmod -R a+rx /usr/local/bin/yq

    # add docker cache
    cat << EOF > /etc/docker/daemon.json
{
    "registry-mirrors":["https://mirror.registry.opennetworking.org"]
}
EOF
    service docker restart

    # remove apparmor
    service apparmor stop
    update-rc.d -f apparmor remove
    apt-get remove apparmor-utils libapparmor-perl apparmor
    update-grub

    # clean up
    apt-get clean
    apt-get purge -y
    apt-get autoremove -y
    rm -rf /var/lib/apt/lists/*
}

all_systems() {
    echo 'No common distribution configuration to perform'
}

echo "---> Detecting OS"
ORIGIN=$(facter operatingsystem | tr '[:upper:]' '[:lower:]')

case "${ORIGIN}" in
    fedora|centos|redhat)
        echo "---> RH type system detected"
        rh_systems
    ;;
    ubuntu)
        echo "---> Ubuntu system detected"
        ubuntu_systems
    ;;
    *)
        echo "---> Unknown operating system"
    ;;
esac

# execute steps for all systems
all_systems

# [EOF]
