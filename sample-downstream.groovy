node {
    git 'https://github.com/jglick/incrementals-downstream-publisher'
    env.DOWNLOAD = "${pwd tmp: true}/download.zip"
    withCredentials([usernameColonPassword(variable: 'USERPASS', credentialsId: '…')]) {
      sh '''
      mvn -B verify
      status=$(curl --silent --output /dev/stderr --write-out '%{http_code}' -i -u $USERPASS -T $DOWNLOAD -H 'X-Explode-Archive: true' -H 'X-Explode-Archive-Atomic: true' https://repo.jenkins-ci.org/incrementals/)
      if [ $status -ne 200 ]; then exit 1; fi
      '''
    }
}
