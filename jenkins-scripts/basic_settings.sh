#!/bin/bash
# @License EPL-1.0 <http://spdx.org/licenses/EPL-1.0>
##############################################################################
# Copyright (c) 2016 The Linux Foundation and others.
#
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Eclipse Public License v1.0
# which accompanies this distribution, and is available at
# http://www.eclipse.org/legal/epl-v10.html
##############################################################################

# set hostname
IPADDR=$(hostname -i)
HOSTNAME=$(hostname -s)
FQDN=$(hostname -f)

cat /etc/hosts
echo "\nDone\n"
echo "$IPADDR"
echo $HOSTNAME
echo $FQDN

echo "${IPADDR} ${HOSTNAME} ${FQDN}" >> /etc/hosts

# Increase limits
cat <<EOF > /etc/security/limits.d/jenkins.conf
jenkins         soft    nofile          16000
jenkins         hard    nofile          16000
EOF

# keepalive SSH sessions
cat <<EOSSH >> /etc/ssh/ssh_config
Host *
  ServerAliveInterval 60
EOSSH

# create host-wide known hosts file
cat <<EOKNOWN >  /etc/ssh/ssh_known_hosts
github.com ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=
[gerrit.opencord.org]:29418 ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQCceEPwEJ5m5tbiL/AB5mY8DfT9UuXsc0l5N4AMxI89g7Vnyb9XOnxubJo2ZmIwDKI6LM5uRCgfIAKmbNNfqA1CL3e/7XKvQ69rrjnM+5swXAvD4ElYppyyU0V9EufuH2AD7zh0VdzqE25TF4nm6A/2neCqcWI7paa8c2h3YbzvHw==
[gerrit.onosproject.org]:29418 ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDgqAmRpkpZoq8Efz4sslaQYnoNCOlPy7nS/72FkvWP06WbPUsutJznSw4moKTZcxHJADW5eanBHxJ3nI8jo/bXC0qHZfzXVeDCklR/Shq8pY3B7I+WLufq4OKEuYim/ahrAYUvSYyBnnz3fLc+DLLiBhL4BBqpd9ocJd/3HZv4wRAWYmfKMKzjF84u6rehe8ZDUoNICsA/K6Wy1bYQnyJOXVBYdxSkdUc6Er1NDu6W/ijZbcpEt+Y4sYoChxKAtsqcFkjaKFgJbotDGVLnWzZTu08PGtZTE+0UyIozSQvsy/7bGSrA7t0am2IRXz0fFNCq/qOWfkwVbt8hRbEIUk/5
[gerrit.onosproject.org]:29418 ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBMBzs9fkmwgIqvYavMlIFz95RzDoSBQxHIeBj2BuDz0HLz2qrW2Q2Rksq4OwsAuSjRto3+9/BgIKv1ONnh21KMM=
[gerrit.onosproject.org]:29418 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIKkIOHzFGowb9yL7FcWD73YF/xDUQ23/As/HAP3flf/L
EOKNOWN

