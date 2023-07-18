/*
added eks:* permissions to AllowAccessFromJenkinsBuildServersToSSMParameters and JenkinsBuilders > JenkinsBuilderPolicy
*/

/*
* Log into EKS cluster and return the context
*/
def setEksCluster(String cluster, String role) {
  def region = "us-west-2"
  def kubectlHome = tool 'kubectl'
  env.PATH = "${kubectlHome}/bin:${env.PATH}"
  env.KUBECONFIG="/tmp/kubeconfig"
  def role_name = (role == null) ? "jenkins-assumed-role" : role
  ceEnv.setSSMCreds(cluster, role_name)

  sh script: """
  aws eks update-kubeconfig --name ${cluster} --kubeconfig ${env.KUBECONFIG}
  """, label: "Set EKS cluster to ${cluster}"
  cluster_context = sh(returnStdout: true, script: "kubectl --kubeconfig ${env.KUBECONFIG} config current-context", label: "Get Cluster Context").trim()
  return cluster_context
}

/*
* Execute a command in a Pod
*/
def kubeExec(Map config) {

  def cluster = config.cluster
  def namespace = config.namespace
  def selector = config.selector
  def command = config.command
  def container = config.get("container", null)
  def containerFlag = (container == null) ? "" : "-c ${container}"
  def label = config.get("label", "Run kubectl command")
  def isEKS = config.get("is_eks", true)
  def context
  if (isEKS) {
    context = setEksCluster(cluster, config.get("role", null))
  } else {
    def kubeconfig = ceEnv.getSSMParameter("/${config.get("environment")}/${config.get("stack")}/admin-kubeconfig")
    env.KUBECONFIG="/tmp/kubeconfig-${cluster}.yaml"
    writeFile file: env.KUBECONFIG, text: kubeconfig
    context = cluster
  }
  def kubectl = "kubectl --context ${context} \
      -n ${namespace}"
  sh script: """
    pod=\$(${kubectl} get pods -l ${selector} -o jsonpath='{.items[0].metadata.name}')
    ${kubectl} exec -i \$pod ${containerFlag} -- ${command}
  """, label: "${label}"
  ceEnv.unsetSSMCreds()
}

/*
* Deploy to a Kubernetes cluster with Helm
*/

def call(Map config) {

  def helmHome = tool 'helm'
  env.PATH = "${helmHome}/bin:${env.PATH}"

  def name = config.name
  def namespace = config.namespace
  def environment = config.environment
  def stack = config.stack
  def cluster = config.cluster
  def rollback = config.get("rollback", false)
  def isEKS = config.get("is_eks", true)
  def context
  if (isEKS) {
    context = setEksCluster(cluster, config.get("role", null))
  } else {
    ceEnv.setSSMCreds(environment, 'jenkins-assumed-role')
    def kubeconfig = ceEnv.getSSMParameter("/${environment}/${stack}/admin-kubeconfig")
    env.KUBECONFIG="/tmp/kubeconfig-${cluster}.yaml"
    writeFile file: env.KUBECONFIG, text: kubeconfig
    context = cluster
  }

  lock("${name}-${namespace}-${environment}-rancher") {
    if (rollback) {
      sh script: """
        VERSION=\$(helm -n ${namespace} --kube-context ${context} history ${name} | grep deployed | cut -d' ' -f1)
        helm -n ${namespace} \
          --kube-context ${context} \
          rollback ${name} \$VERSION
      """, label: "Rolling back ${name}"
    } else {
      def chart = config.chart
      def timeout = config.get("timeout", "900s")
      def args = config.get("args", "")
      def values_files = config.get("values_files", "values.yaml")
      def values = values_files.split(",").join(" -f ${chart}/")

      sh script: """
      helm repo add cccnext https://charts.ci.ccctechcenter.org --force-update
      helm dependency update ${chart}
      helm upgrade -i ${name} \
          --kubeconfig ${env.KUBECONFIG} \
          -n ${namespace} \
          --wait --timeout ${timeout} \
          --create-namespace \
          --history-max 50 \
          --atomic \
          ${args} \
          --kube-context ${context} \
          -f ${chart}/${values} ${chart}
      """, label: "Deploy ${name}"
    }
  }

  ceEnv.unsetSSMCreds()
}
