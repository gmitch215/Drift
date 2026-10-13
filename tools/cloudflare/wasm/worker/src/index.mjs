import { importObject, setWasmExports } from './kotlin.import-object.mjs';
import bytes from './kotlin.wasm';

const wasmOptions = { builtins: ['js-string'], importedStringConstants: "'" };
const module = new WebAssembly.Module(bytes, wasmOptions);
const instance = new WebAssembly.Instance(module, importObject);
setWasmExports({ memory: importObject.intrinsics.memory });
instance.exports._start();

export default {
    fetch() {
        return new Response(instance.exports.transcript('cloudflare-wasm'), {
            headers: { 'content-type': 'text/plain; charset=utf-8' }
        });
    }
};
