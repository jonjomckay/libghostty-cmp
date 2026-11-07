// Skiko bypasses Fetch for file://. Give its modular Emscripten factory local bytes explicitly.
module.exports = function (source) {
  const marker = 'loadSkikoWASM()';
  if (source.split(marker).length !== 2) throw new Error('Skiko loader contract changed; review offline startup');
  return source.replace(marker, "loadSkikoWASM(globalThis.__ghosttyAsset ? {wasmBinary:globalThis.__ghosttyAsset('skiko.wasm')} : {})");
};
