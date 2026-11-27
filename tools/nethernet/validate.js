// Valida los paquetes que genera la app (BedrockPackets.kt) contra las definiciones de
// bedrock-protocol para cada versión 26.x conocida. Uso: CP=<classpath> node validate.js
const { execFileSync } = require('child_process')
const { createDeserializer } = require('bedrock-protocol/src/transforms/serializer')
const { Versions } = require('bedrock-protocol/src/options')
const MAX_SUPPORTED = 2259 // BedrockVersions.MAX_SUPPORTED en la app

const versions = Object.entries(Versions).filter(([, p]) => p >= 2168)
let failures = 0
for (const [version, protocol] of versions) {
  const out = execFileSync('java', ['-cp', process.env.CP, 'com.bedrocklanproxy.core.DumpPacketsKt', String(protocol)], { encoding: 'utf8' })
  const d = createDeserializer(version)
  const bad = []
  for (const line of out.trim().split('\n')) {
    const [name, hex] = line.split(' ')
    const buf = Buffer.from(hex, 'hex')
    try {
      const res = d.parsePacketBuffer(buf)
      if (res.metadata.size !== buf.length || res.data.name !== name) bad.push(`${name} (leído ${res.metadata.size}/${buf.length} como ${res.data.name})`)
    } catch (e) {
      bad.push(`${name} (${e.message.split('\n')[0].slice(0, 120)})`)
    }
  }
  failures += bad.length
  console.log(`${bad.length ? 'FALLA' : 'OK   '} ${version} (protocolo ${protocol})${bad.length ? ': ' + bad.join('; ') : ''}`)
}
const newest = Math.max(...versions.map(([, p]) => p))
if (newest > MAX_SUPPORTED) {
  console.log(`AVISO: bedrock-protocol ya conoce el protocolo ${newest}, más nuevo que el máximo contemplado por la app (${MAX_SUPPORTED}). Revisa los cambios de formato y sube BedrockVersions.MAX_SUPPORTED.`)
}
process.exit(failures ? 1 : 0)
