package com.bedrocklanproxy.core

/** Imprime los paquetes en hex para validarlos con bedrock-protocol (herramienta de desarrollo). */
fun main(args: Array<String>) {
    val p = args.getOrNull(0)?.toInt() ?: 2225
    fun hex(name: String, b: ByteArray) = println(name + " " + b.joinToString("") { "%02x".format(it) })
    hex("network_settings", BedrockPackets.networkSettings(p))
    hex("play_status", BedrockPackets.playStatus(0))
    hex("resource_packs_info", BedrockPackets.resourcePacksInfo(p))
    hex("resource_pack_stack", BedrockPackets.resourcePackStack(p))
    hex("jigsaw_structure_data", BedrockPackets.jigsawStructureData())
    hex("voxel_shapes", BedrockPackets.voxelShapes())
    hex("start_game", BedrockPackets.startGame(p, "Hardlands"))
    hex("item_registry", BedrockPackets.itemRegistry())
    hex("chunk_radius_update", BedrockPackets.chunkRadiusUpdated(4))
    hex("creative_content", BedrockPackets.creativeContent())
    hex("transfer", BedrockPackets.transfer(p, "172.96.172.240", 25776))
    hex("disconnect", BedrockPackets.disconnect(p, "adios"))
}
