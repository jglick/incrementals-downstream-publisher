node {
  checkout scm
  if (fileExists('.mvn/extensions.xml')) {
    def changelistF = "${pwd tmp: true}/changelist"
    sh "mvn -DskipTests -Dset.changelist help:evaluate -Dexpression=changelist -Doutput=$changelistF clean install"
    def changelist = readFile(changelistF)
    dir("$HOME/.m2/repository") {
      archiveArtifacts artifacts: "**/*$changelist/*$changelist*", excludes: '**/*.lastUpdated'
    }
    build 'downstream'
  }
}
