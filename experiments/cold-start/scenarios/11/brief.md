My log check passes on my laptop. The check asks for the lines since 12:00, and it finds 4 of them there. On the build server the same check fails with this:

AssertionError: expected 4 lines, got 1

Someone told me to use drift. The code is the same copy on both machines. The check is `test.py` and the log is `app.log`, both in this folder. Tell me what is different and what I should change so the check passes on the server.
