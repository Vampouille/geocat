#!/bin/bash

sudo -u postgres /usr/lib/postgresql/9.1/bin/postgres &
/wait-for-it.sh localhost:5432

