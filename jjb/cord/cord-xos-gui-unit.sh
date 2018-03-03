#!/bin/bash -ex

# Install npm deps
npm install
typings install

# Check code style
npm run lint

# Execute tests
npm test