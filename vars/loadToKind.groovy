// loads all the images tagged as citest on a Kind cluster

def call(Map config) {
  def defaultConfig = [
    name: "kind-ci"
  ]

  if (!config) {
      config = [:]
  }

  def cfg = defaultConfig + config

  def images = sh (
    script: 'docker images -f "reference=**/*citest" --format "{{.Repository}}"',
    returnStdout: true
  ).trim()

  def list = images.split("\n")

  for(int i = 0;i<list.size();i++) {
    def image = list[i]
    println "Loading image ${image} on Kind cluster ${cfg.name}"

    sh """
      kind load docker-image ${image}:citest --name ${cfg.name} --nodes ${cfg.name}-worker,${cfg.name}-worker2
    """
  }
}
