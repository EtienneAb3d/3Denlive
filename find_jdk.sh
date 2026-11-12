# Sourced by run_3De.sh and build_delivery.sh (needs ROOT set): finds a Java tool of a
# minimum major version, whatever the default java / javac of the system.
#
#   find_java_bin <min major> <tool>   (tool: javac, java, jar...)
# prints the bin/ folder holding that tool, or nothing. Searched in this order:
#   $JAVA_HOME/bin, the tool on the PATH, JVMs/JDK/bin (./Install-Dev.sh --jdk-only),
#   then the installed JDKs, most recent first: /usr/lib/jvm/*, /Library/Java/JavaVirtualMachines/*,
#   ~/Library/Java/JavaVirtualMachines/*, ~/.jdks/*, ~/.sdkman/candidates/java/*.

# Major version of a java / javac binary (8 for 1.8), 0 when it cannot run
java_major() {
	local v
	v="$("$1" -version 2>&1 | grep -oE '(javac |version ")[0-9]+(\.[0-9]+)?' | head -1 | grep -oE '[0-9]+(\.[0-9]+)?' || true)"
	case "$v" in 1.*) v="${v#1.}" ;; esac
	v="${v%%.*}"
	case "$v" in ''|*[!0-9]*) echo 0 ;; *) echo "$v" ;; esac
}

find_java_bin() {
	local min="$1" tool="$2" d onpath
	local dirs=()
	[ -n "${JAVA_HOME:-}" ] && dirs+=("$JAVA_HOME/bin")
	onpath="$(command -v "$tool" 2>/dev/null || true)"
	[ -n "$onpath" ] && dirs+=("$(dirname "$onpath")")
	dirs+=("$ROOT/JVMs/JDK/bin")
	while IFS= read -r d; do dirs+=("$d"); done < <(
		for d in /usr/lib/jvm/*/bin \
			/Library/Java/JavaVirtualMachines/*/Contents/Home/bin \
			"$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home/bin \
			"$HOME"/.jdks/*/bin "$HOME"/.sdkman/candidates/java/*/bin; do
			[ -d "$d" ] && echo "$d"
		done | sort -rV)
	for d in "${dirs[@]}"; do
		[ -x "$d/$tool" ] || continue
		# jar has no -version of its own: check the javac / java next to it
		if [ "$tool" = jar ]; then
			[ -x "$d/javac" ] && [ "$(java_major "$d/javac")" -ge "$min" ] && { echo "$d"; return; }
		elif [ "$(java_major "$d/$tool")" -ge "$min" ]; then
			echo "$d"; return
		fi
	done
}
