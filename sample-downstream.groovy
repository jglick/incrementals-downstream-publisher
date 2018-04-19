properties([parameters([string(name: 'UPSTREAM_URL', description: 'URL of upstream build.')])])
node {
    git 'https://github.com/jglick/incrementals-downstream-publisher'
    env.DOWNLOAD = "${pwd tmp: true}/download.zip"
    env.ALLOWED_UPSTREAM_FOLDERS = "${JENKINS_URL}job/jenkinsci/"
    env.DEPLOY_URL = 'https://repo.jenkins-ci.org/incrementals/'
    env.RPU_INDEX = 'https://ci.jenkins.io/job/Infra/job/repository-permissions-updater/job/master/lastSuccessfulBuild/artifact/json/github-index.json'
    withCredentials([usernameColonPassword(variable: 'DEPLOY_AUTH', credentialsId: 'artifactory'),
                     usernameColonPassword(variable: 'GITHUB_AUTH', credentialsId: 'github')]) {
      sh 'mvn -B verify'
    }
}
