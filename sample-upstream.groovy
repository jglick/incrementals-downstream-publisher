node {
  checkout scm
  sh '''
if [ -f .mvn/extensions.xml ]
then
  mvn -DskipTests -Dset.changelist clean install
fi
'''
  def changelist = sh(returnStdout: true, script: 'echo -n -rc$(git rev-list --first-parent --count HEAD).$(git rev-parse --short=12 HEAD)')
  dir("$HOME/.m2/repository") {
    archiveArtifacts "**/*$changelist/*$changelist*"
  }
  build 'downstream'
}
