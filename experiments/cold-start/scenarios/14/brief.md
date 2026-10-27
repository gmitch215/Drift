My download import check passes on my laptop. On the build server it fails with this:

AssertionError: importer cannot write: album mode is 755

The importer runs as a different user in the same group, so it needs write access to the album folder. I have not changed the code. Someone told me to use drift. The check is `test.py` in this folder. Tell me what is different on the server and what I should change so the check passes there.
