#!/bin/bash -ex

# Install npm deps
npm install

# Install typings deps
# typings install

# Check code style
npm run lint

# Execute tests
npm test