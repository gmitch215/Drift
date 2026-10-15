# python-frameworks

`requirements.txt` pins one release of each framework the Python framework rules read. `app.py` uses
one name from each removed or changed API list so every source signal fires:

- Django 4.0 `ugettext_lazy`, 5.0 `datetime_safe`, 5.1 `get_storage_class`
- NumPy 2.0 `np.NaN`
- Pydantic 2 `BaseSettings` and `ConstrainedInt`
- Flask 2.3 `before_first_request`
- pandas 2.0 `iteritems`, 3.0 `applymap`
- PyTorch 2.6 `torch.load` without `weights_only`
- `import cx_Oracle` (Django 6.0 drops cx_Oracle)

The file is written for the scanner and is not meant to run.
