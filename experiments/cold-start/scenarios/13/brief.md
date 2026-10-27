My index builder passes on my laptop. On the build server it fails partway through with this:

OSError: [Errno 24] Too many open files

I have not changed the code. Someone told me to use drift. The check is `test.py` in this folder. Tell me what is different between the two machines and what I should change so the check passes on the server.
