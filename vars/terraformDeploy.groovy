/* 
* Run Terragrunt
*/

def call(Map config) {

  def tfHome = tool 'terraform'
  def tgHome = tool 'terragrunt'
  env.PATH = "${tfHome}/bin:${tgHome}/bin:${env.PATH}"

  def command = config.get("command", "terragrunt")
  def initCommand = (command == "terraform") ? "&& terraform init" : ""
  def action = config.get("action", "plan")
  def args = config.get("args", "")
  def path = config.get("path")
  def environment = config.get("env")

  sh script: """
  sleep 3
  cd ${path} ${initCommand} && ${command} ${action} ${args}
  """, label: "${command} ${action} ${args}"
}
