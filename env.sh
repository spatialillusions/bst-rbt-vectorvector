# Source to use the project-local toolchain from Git Bash:  source ./env.sh
VV_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="$(ls -d "$VV_ROOT"/.tools/jdk-21* | head -1)"
export PATH="$JAVA_HOME/bin:$(ls -d "$VV_ROOT"/.tools/apache-maven-* | head -1)/bin:$VV_ROOT/.tools/bin:$PATH"
cd "$VV_ROOT"
