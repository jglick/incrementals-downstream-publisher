node {
  checkout scm
  if (fileExists('.mvn/extensions.xml')) {
    def changelistF = "${pwd tmp: true}/changelist"
    sh "mvn -B -Dset.changelist help:evaluate -Dexpression=changelist -Doutput=$changelistF clean install"
    def changelist = readFile(changelistF)
    dir("$HOME/.m2/repository") {
      archiveArtifacts artifacts: "**/*$changelist/*$changelist*", excludes: '**/*.lastUpdated'
      // Note: could also add exclusions for any modules configured with -Dmaven.deploy.skip: e.g., `jenkinsci/jenkins/test`.
    }
    build job: 'downstream', parameters: [string(name: 'UPSTREAM_URL', value: BUILD_URL)], quietPeriod: 0
  }
}
