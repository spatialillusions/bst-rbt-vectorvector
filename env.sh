# Source to use the project-local toolchain from Git Bash:  source ./env.sh
VV_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -n "${JDK21_HOME:-}" ]]; then export JAVA_HOME="$JDK21_HOME"; fi
for VV_JDK in "$VV_ROOT"/.tools/jdk-21*; do
    if [[ -d "$VV_JDK/bin" ]]; then export JAVA_HOME="$VV_JDK"; break; fi
done
for VV_MAVEN in "$VV_ROOT"/.tools/apache-maven-*; do
    if [[ -d "$VV_MAVEN/bin" ]]; then export PATH="$VV_MAVEN/bin:$PATH"; break; fi
done
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [[ -d "$VV_ROOT/.tools/bin" ]]; then export PATH="$VV_ROOT/.tools/bin:$PATH"; fi
unset VV_JDK VV_MAVEN
cd "$VV_ROOT"
