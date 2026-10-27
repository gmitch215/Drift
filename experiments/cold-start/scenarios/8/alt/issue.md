Test run stops on the CI runner

Environment:

Python: 3.12.4 on the runner and on my laptop

The suite passes on my laptop. On the runner it stops after about 250 tests with "OSError: [Errno 24] Too many open files". I see no error locally.
