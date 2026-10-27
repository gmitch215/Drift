from distutils.version import LooseVersion

assert LooseVersion("1.2") < LooseVersion("1.10")
print("PASS")
