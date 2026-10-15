import cx_Oracle
import numpy as np
import torch
from django.core.files.storage import get_storage_class
from django.utils import datetime_safe
from django.utils.translation import ugettext_lazy as _
from flask import Flask
from pydantic import BaseSettings, ConstrainedInt

app = Flask(__name__)


@app.before_first_request
def setup():
    return np.NaN


def load(frame, path):
    frame.applymap(str)
    for _, row in frame.iteritems():
        print(row)
    return torch.load(path)
