#!/usr/bin/env bash
# Prints the environment block of a claim evidence report (see SKILL.md, "Claim Evidence Report").
#
# Usage: claim-environment.sh [--java <java executable>] [--no-species]
#
# Run it right before and right after the measured run. Pass --java with the JDK the benchmark runs on (for
# Gradle tasks, the toolchain JDK), since JAVA_HOME or PATH may name another one. Every value the host cannot
# provide is printed as "unavailable", never guessed. The script reads only local system information and starts
# at most one JVM (to print the Vector API species); it changes nothing and needs no network.
set -u

java_bin=""
probe_species=1
while [ $# -gt 0 ]; do
  case "$1" in
    --java)
      if [ $# -lt 2 ] || [ -z "$2" ]; then echo "--java needs a path" >&2; exit 2; fi
      java_bin="$2"; shift 2 ;;
    --no-species) probe_species=0; shift ;;
    -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$java_bin" ]; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java_bin="$JAVA_HOME/bin/java"
  else
    java_bin="$(command -v java || true)"
  fi
fi

value() {
  if [ -n "$1" ]; then printf '%s' "$1"; else printf 'unavailable'; fi
}

row() {
  # Escape pipes so a value cannot break the Markdown table.
  printf '| %s | %s |\n' "$1" "$(value "$2" | sed 's/|/\\|/g')"
}

os="$(uname -sr 2>/dev/null || true)"
arch="$(uname -m 2>/dev/null || true)"
cpu=""
cpus=""
simd=""
memory=""
load=""

if [ -r /proc/cpuinfo ]; then
  cpu="$(grep -m1 -E '^(model name|Model|Hardware)' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//')"
  cpus="$(grep -c '^processor' /proc/cpuinfo)"
  simd="$(grep -m1 -E '^(flags|Features)' /proc/cpuinfo | cut -d: -f2- | tr ' ' '\n' \
    | grep -x -E 'sse4_2|avx|avx2|fma|avx512f|avx512vl|asimd|sve|sve2' | sort -u | tr '\n' ' ' | sed 's/ $//')"
elif command -v sysctl >/dev/null 2>&1; then
  cpu="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || true)"
  cpus="$(sysctl -n hw.logicalcpu 2>/dev/null || true)"
  simd="$(sysctl -n machdep.cpu.features machdep.cpu.leaf7_features 2>/dev/null | tr ' ' '\n' \
    | grep -i -x -E 'avx1.0|avx2|fma|avx512f' | tr '\n' ' ' | sed 's/ $//')"
  if [ -z "$simd" ] && [ "$(sysctl -n hw.optional.neon 2>/dev/null || true)" = "1" ]; then simd="neon"; fi
fi

if [ -r /proc/meminfo ]; then
  memory="$(awk '/^MemTotal:/ {t=$2} /^MemAvailable:/ {a=$2} END {if (t) printf "%.1f GiB total, %.1f GiB available", t/1048576, a/1048576}' /proc/meminfo)"
elif command -v sysctl >/dev/null 2>&1; then
  bytes="$(sysctl -n hw.memsize 2>/dev/null || true)"
  if [ -n "$bytes" ]; then memory="$(awk -v b="$bytes" 'BEGIN {printf "%.1f GiB total", b/1073741824}')"; fi
fi

if [ -r /proc/loadavg ]; then
  load="$(cut -d' ' -f1-3 /proc/loadavg) (1, 5, 15 min)"
elif command -v sysctl >/dev/null 2>&1; then
  load="$(sysctl -n vm.loadavg 2>/dev/null | tr -d '{}' | sed 's/^ *//;s/ *$//') (1, 5, 15 min)"
fi

revision=""
if git rev-parse --git-dir >/dev/null 2>&1; then
  revision="$(git rev-parse HEAD 2>/dev/null || true)"
  if [ -n "$(git status --porcelain 2>/dev/null)" ]; then revision="$revision (working tree has changes)"; fi
fi

jdk=""
species=""
if [ -n "$java_bin" ] && { [ ! -f "$java_bin" ] || [ ! -x "$java_bin" ]; }; then
  echo "warning: java executable not found or not executable: $java_bin" >&2
fi
if [ -n "$java_bin" ] && [ -f "$java_bin" ] && [ -x "$java_bin" ]; then
  # Drop "Picked up ..." lines (JAVA_TOOL_OPTIONS, and "NOTE: Picked up JDK_JAVA_OPTIONS") so the runtime line is found.
  jdk="$("$java_bin" -version 2>&1 | grep -v 'Picked up ' | sed -n '2p' | sed 's/^ *//')"
  if [ "$probe_species" -eq 1 ]; then
    probe_dir="$(mktemp -d)"
    cat > "$probe_dir/Species.java" <<'JAVA'
public class Species {
    public static void main(String[] args) {
        var species = jdk.incubator.vector.DoubleVector.SPECIES_PREFERRED;
        System.out.println(species + ", " + species.length() + " double lanes");
    }
}
JAVA
    species="$("$java_bin" --add-modules jdk.incubator.vector "$probe_dir/Species.java" 2>/dev/null | tail -n1)"
    rm -rf "$probe_dir"
  fi
fi
if [ "$probe_species" -eq 0 ]; then species="not probed (--no-species)"; fi

printf '| Field | Value |\n| :--- | :--- |\n'
row "Captured (UTC)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
row "Revision" "$revision"
row "OS" "$os $arch"
row "CPU" "$cpu"
row "Logical CPUs" "$cpus"
row "SIMD features" "$simd"
row "Memory" "$memory"
row "Load average" "$load"
row "JDK" "$jdk"
row "Java executable" "$java_bin"
row "Vector API preferred species" "$species"
row "JAVA_TOOL_OPTIONS" "${JAVA_TOOL_OPTIONS:-none}"
row "JDK_JAVA_OPTIONS" "${JDK_JAVA_OPTIONS:-none}"
