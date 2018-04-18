properties([parameters([string(name: 'UPSTREAM_URL', description: 'URL of upstream build.')])])
node {
    git 'https://github.com/jglick/incrementals-downstream-publisher'
    env.DOWNLOAD = "${pwd tmp: true}/download.zip"
    env.ALLOWED_UPSTREAM_FOLDERS = "${JENKINS_URL}job/jenkinsci/"
    withCredentials([usernameColonPassword(variable: 'DEPLOY_USERPASS', credentialsId: '…'), usernameColonPassword(variable: 'GITHUB_AUTH', credentialsId: 'github')]) {
      sh '''
      mvn -B verify
      status=$(curl --silent --output /dev/stderr --write-out '%{http_code}' -i -u $DEPLOY_USERPASS -T $DOWNLOAD -H 'X-Explode-Archive: true' -H 'X-Explode-Archive-Atomic: true' https://repo.jenkins-ci.org/incrementals/)
      if [ $status -ne 200 ]; then exit 1; fi
      '''
    }
}
