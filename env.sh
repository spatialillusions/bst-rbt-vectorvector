# Source to use the project-local toolchain from Git Bash:  source ./env.sh
VV_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VV_JDK="${JDK25_HOME:-${JAVA_HOME:-}}"
if [[ -z "$VV_JDK" && -n "${LOCALAPPDATA:-}" ]]; then
    VV_JDK="$LOCALAPPDATA/Programs/Eclipse Adoptium/jdk-25.0.4.1+1"
fi
if [[ -n "$VV_JDK" ]]; then
    if command -v cygpath >/dev/null 2>&1; then VV_JDK="$(cygpath -u "$VV_JDK")"; fi
    if [[ -d "$VV_JDK/bin" ]]; then export JAVA_HOME="$VV_JDK"; fi
fi
for VV_MAVEN in "$VV_ROOT"/../bst-rbt-planetiler/.tools/maven/apache-maven-3.9.11 "$VV_ROOT"/.tools/apache-maven-*; do
    if [[ -d "$VV_MAVEN/bin" ]]; then export PATH="$VV_MAVEN/bin:$PATH"; break; fi
done
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [[ -d "$VV_ROOT/.tools/bin" ]]; then export PATH="$VV_ROOT/.tools/bin:$PATH"; fi
unset VV_JDK VV_MAVEN
cd "$VV_ROOT"
