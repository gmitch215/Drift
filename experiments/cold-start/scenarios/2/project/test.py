import tomllib

config = tomllib.loads('name = "orders"\n')
assert config["name"] == "orders"
print("PASS")
