#!/bin/bash

defaultFound=0
defaultUser=""
if [[ -d /home ]]; then
  Base="/home"
else
    if [[ -d /Users ]]; then
      Base="/Users" 
    else
      echo "Neither /home or /Users present"
      exit -1
    fi
fi
for defUser in ubuntu centos ec2-user admin daf
do
  if [ $defaultFound -eq 1 ]; then 
    echo "Multiple possible default accounts found: using $defUser"
  fi
  if [[ -d $Base/$defUser ]]; then 
    echo "$defUser default user"
    defaultFound=1
  fi
done
if [ $defaultFound -ne 1 ]; then
  echo "No default root account found"
  exit -1
fi
