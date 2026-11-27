// Simula una consola 26.60+ que entra en la partida LAN de la app por NetherNet y comprueba
// que recibe el paquete Transfer. Uso: node join.js [host-del-proxy]
const bedrock = require('bedrock-protocol')
const { Versions } = require('bedrock-protocol/src/options')
const version = process.env.MC_VERSION || Object.keys(Versions)[0] // la más nueva
const host = process.argv[2] || '127.0.0.1'
const t0 = Date.now(); const ts = () => ((Date.now() - t0) / 1000).toFixed(1) + 's'
console.log(`Consola de prueba: Minecraft ${version} (protocolo ${Versions[version]}) por NetherNet`)
setTimeout(() => { console.log(ts(), 'FALLA: no llegó el Transfer a tiempo'); process.exit(1) }, 40000)
const client = bedrock.createClient({ transport: 'nethernet', host, offline: true, username: 'Prueba', version })
for (const ev of ['network_settings', 'play_status', 'resource_packs_info', 'resource_pack_stack', 'start_game', 'item_registry', 'chunk_radius_update', 'creative_content']) {
  client.on(ev, p => console.log(ts(), 'recibido', ev, ev === 'play_status' ? p.status : ''))
}
client.on('disconnect', p => { console.log(ts(), 'FALLA: desconectado', JSON.stringify(p)); process.exit(1) })
client.on('transfer', p => {
  console.log(ts(), 'OK: Transfer recibido ->', JSON.stringify(p))
  client.close()
  setTimeout(() => process.exit(0), 500)
})
client.on('error', e => console.log(ts(), 'error', e && e.message))
