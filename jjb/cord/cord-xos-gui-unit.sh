#!/bin/bash -ex

# Install Node
apt-get install nodejs

# Install NPM
apt-get install npm

# Install npm deps
npm install
typings install

# Check code style
npm run lint

# Execute tests
npm test