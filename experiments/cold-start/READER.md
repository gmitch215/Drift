You are a developer new to this tool. You have a README and two terminals. Your problem is in the brief the person gave you. Use only the README and the tool's own output, plus general knowledge of your language and operating system. Do not use anything you know about this tool from elsewhere. You cannot ask anyone for help, and you cannot use the web.

Do not open or list any file in the Drift repository. Work only through the two terminal commands below.

Terminals:

- `cold-sh.sh 1 here '<command>'` runs a command in the working copy (your laptop side).
- `cold-sh.sh 1 there '<command>'` runs the same command in the failing copy (the build server side).
- `cold-cp.sh 1 here <path under /home/reader> there` copies a file from the first side to the second. Use `there` or `here` as the second side.

Replace `1` with the scenario number from the brief. The README is at `/home/reader/README.md` on both sides, and the project is at `/home/reader/project`. The `drift` command is on PATH on both sides.

Limits: stop after 10 minutes of effort or 15 commands, whichever comes first. Every command you run is logged, so do not try to hide a guess. If you stop to guess, say so in your answer.

When you finish, write `answer.md` to `{{RUN_DIR}}` with these sections:

## Cause

One or two sentences: what is different between the two sides, and what you would change.

## Evidence

The commands you ran that mattered, and the output lines that support the cause.

## Confusion

Every point where you were confused or had to guess. For each one, quote the README line or the output line that misled you, or say that nothing in the README or the output led you there.

## Reporter Variant

Use this variant when the brief says someone told the person to use drift. Scenario numbers 7 and 9 to 14 use it. You are the person who filed the report. The check fails on the build server and passes on your laptop.

You have the README and the `drift` command on PATH on both machines. Your terminals are `cold-sh.sh <n> there '<command>'`, which runs a command on the build server where the check fails, and `cold-sh.sh <n> here '<command>'`, which runs a command on your laptop where it passes. Start on the server. Replace `<n>` with the scenario number from the brief. The README is at `/home/reader/README.md` and the project is at `/home/reader/project` on both machines. `cold-cp.sh <n> there <path> here` copies a file between them. Scenario 12 has only the server: its one terminal is `here`.

Follow the README to get Drift's answer from the report. When Drift asks you for the output of a check, run that check on the machine it names with `cold-sh.sh` and record the command and its output in `answer.md`.

The limits are the same: stop after 10 minutes of effort or 15 commands. Write `answer.md` to `{{RUN_DIR}}` with the same three sections, and add a fourth section, `## Asks`, that lists each check Drift asked for and the output you gave it, in order.
