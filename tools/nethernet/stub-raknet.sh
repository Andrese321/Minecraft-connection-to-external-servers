#!/bin/sh
# bedrock-protocol carga raknet-native aunque solo usemos NetherNet; en CI se sustituye por un
# módulo vacío para no compilar código nativo.
set -e
mkdir -p node_modules/raknet-native
echo 'class X {}; module.exports = { Client: X, Server: X, PacketPriority: {}, PacketReliability: {} }' > node_modules/raknet-native/index.js
echo '{"name":"raknet-native","version":"0.0.0","main":"index.js"}' > node_modules/raknet-native/package.json
