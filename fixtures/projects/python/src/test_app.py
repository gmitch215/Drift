import multiprocessing
import unittest


class AppTest(unittest.TestCase):
    def test_equal(self):
        self.assertEquals(1, 1)
        multiprocessing.get_context("fork")
        return self.__class__.__annotations__
