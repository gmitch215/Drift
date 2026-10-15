import aifc, audioop, cgi, cgitb, chunk, crypt, imghdr, mailcap, msilib, nis
import nntplib, ossaudiodev, pipes, sndhdr, spwd, sunau, telnetlib, uu, xdrlib
import asynchat
import asyncore
import imp
import ssl


def wrap(sock):
    return ssl.wrap_socket(sock)


def check(cert, host):
    ssl.match_hostname(cert, host)
