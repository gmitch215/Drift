#!/usr/bin/env bash
# usage: reader-prompt.sh <n> <run-dir>   print the prompt for one reader agent
set -eu
. "$(dirname "$0")/harness.sh"

n=${1:?scenario number}
run=${2:?run directory}
scen="$COLD_DIR/scenarios/$n"
. "$scen/scenario.env"
sh="$COLD_DIR/cold-sh.sh"
cp="$COLD_DIR/cold-cp.sh"
brief=$(cat "$scen/brief.md")

cat <<EOF
You are a developer new to a command line tool called drift. You are the person in the brief below. Use only the README and the tool's own output, plus general knowledge of your language and operating system. Do not use anything you know about this tool from elsewhere. You cannot ask anyone for help and you cannot use the web.

Do not open, list or search any file in $REPO except the scripts named below. Work only through the terminal commands below.

THE BRIEF (from the person who asked you to help; you are that person):
"$brief"
EOF

if [ "$n" -ge 9 ]; then
	echo 'Someone told you: "just use drift".'
fi

echo
if [ "${MODE:-pair}" = single ]; then
	cat <<EOF
YOUR TERMINAL: you are on the build server, the machine where the check fails. You reach it with:
  $sh $n here '<command>'
You have no access to your laptop in this test; if drift needs something from it, say so in your answer.
EOF
else
	cat <<EOF
YOUR TERMINALS (two machines, each reached by a script):
  $sh $n there '<command>'   runs a command on the machine where the check fails (the build server)
  $sh $n here '<command>'    runs a command on the machine where it works (your laptop)
  $cp $n there <path under /home/reader> here   copies a file from one machine to the other (use there or here as the last word)
Start on the machine where it fails (there).
EOF
fi

cat <<EOF
The README is at /home/reader/README.md on every machine (read it with the script, for example: cat /home/reader/README.md). The project is at /home/reader/project. The drift command is on PATH on every machine.

Follow the README to get drift's answer. When drift asks you for the output of a check, run that check on the machine it names with the script and give the output to drift the way drift says.

Limits: stop after 10 minutes of effort or 15 commands, whichever comes first. Every command is logged; do not hide a guess. If you stop to guess, say so.

When you finish, your final reply is your answer. Reply with exactly these four sections and nothing else:
## Cause - one or two sentences: what is different and what you would change.
## Evidence - the commands that mattered and the output lines that support the cause.
## Confusion - every point where you were confused or had to guess; quote the README line or output line that misled you, or say nothing led you there.
## Asks - each check drift asked for and the output you gave it, in order (write "none" if drift asked for nothing).
EOF
