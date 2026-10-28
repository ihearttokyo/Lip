// loader.hpp — read parakeet-tdt config + mel constants from a loaded GGUF.
//
// Wraps the shared starling::ggml::ModelLoader: reads the parakeet.* KV metadata
// into a Config and exposes the mel filterbank/window tensors (baked into the
// GGUF as preprocessor.featurizer.{fb,window}).

#pragma once

#include "config.hpp"
#include "runtime/model_loader.hpp"

#include <string>

namespace starling::ggml::parakeet {

// A parakeet model bound to a loaded GGUF: config + the shared loader (for
// weight tensors, realized to the device by realize_weights).
struct ParakeetModel {
    Config config;
    ModelLoader loader;  // owns the weight tensors + GGUF context

    // Load + parse. Returns true on success; on failure sets err.
    bool load(const char* gguf_path, std::string& err);
    // Borrowed regular FD; see ModelLoader::load_fd for ownership/shared-offset
    // and mapped-byte stability requirements. This is not a sealed model pin.
    bool load_fd(int fd, std::string& err);
private:
    bool configure_loaded(std::string& err);
};

} // namespace starling::ggml::parakeet
