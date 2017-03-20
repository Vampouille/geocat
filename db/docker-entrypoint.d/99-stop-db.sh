#!/bin/bash

while :
do
	killall postgres
	ps aux | grep postgres
	if [[ $result -ne 0 ]]; then
		break;
	fi
	sleep 1
done

