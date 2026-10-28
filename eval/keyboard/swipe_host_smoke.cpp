// Lip diagnostic only: real pinned neural model + tiny synthetic lexicon/path controls.
// This is NOT human swipe quality, Gboard comparison, Android timing or a9.2 gate.
#include "swipekb/swipe_net.hpp"
#include "swipekb/swipe_beam.hpp"
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <vector>
#include <chrono>

static void require(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
static void array(const std::vector<float>& values) {
    std::cout << '[';
    for (size_t i = 0; i < values.size(); ++i) { if (i) std::cout << ','; std::cout << values[i]; }
    std::cout << ']';
}
int main(int argc, char** argv) {
    try {
        require(argc == 2, "Expected pinned swipe-model.bin");
        swipekb::SwipeNet model;
        require(model.load(argv[1]) && model.ready() && model.steps() == 64, "Pinned model did not load");
        swipekb::SwipeNet bad;
        require(!bad.loadBytes({'b','a','d'}) && !bad.ready(), "Invalid model accepted");
        std::vector<swipekb::SwipeKey> keys;
        const std::vector<std::string> rows{"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        for (size_t row = 0; row < rows.size(); ++row) {
            const float offset = row == 0 ? 0.f : row == 1 ? .5f : 1.5f;
            for (size_t c = 0; c < rows[row].size(); ++c)
                keys.push_back({char32_t(rows[row][c]), (c + offset + .5f)/10.f, (row+.5f)/3.f, .045f, .15f});
        }
        swipekb::SwipeLayout layout(keys);
        const auto letters = layout.wordKeys("hello");
        std::vector<swipekb::SwipePoint> trace;
        for (size_t j = 0; j < letters.size(); ++j) {
            const auto& key = keys[letters[j]];
            if (!j) { trace.push_back({key.cx, key.cy, 0}); continue; }
            const auto previous = trace.back();
            for (int step=1; step<=20; ++step) {
                const float f = step/20.f;
                trace.push_back({previous.x+(key.cx-previous.x)*f,previous.y+(key.cy-previous.y)*f,previous.t+step*10.});
            }
        }
        swipekb::SwipeInput input;
        require(model.buildInput(trace, layout, .45f, input), "Feature construction failed");
        std::vector<float> logp;
        const auto start = std::chrono::steady_clock::now();
        require(model.forward(input, logp), "Neural forward failed");
        const auto elapsed = std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
        require(logp.size() == 64*27, "Wrong model output shape");
        for (int t=0;t<64;++t) {
            double sum=0;
            for (int k=0;k<27;++k) { const float value=logp[t*27+k]; require(std::isfinite(value),"Non-finite probability"); sum+=std::exp(value); }
            require(std::abs(sum-1)<1e-4,"Output does not normalize");
        }
        swipekb::SwipeLexicon lex;
        for (const std::string word : {"hello","help","held","world","keyboard","meeting","friday","test"}) lex.add(word,200,layout);
        lex.finish(); swipekb::SwipeBeamSearch decoder(lex);
        auto candidates = decoder.decode(logp.data(),64,26,swipekb::BeamParams{},3);
        require(!candidates.empty(),"No candidates");
        bool hello=false;
        for (const auto& c:candidates) if (lex.text(c.word)=="hello") hello=true;
        require(hello,"Synthetic HELLO path missed top3; investigate input/runner, do not change reference");
        std::cout << std::setprecision(9) << "{\"evidence\":\"real neural forward; synthetic path and tiny lexicon only\",\"parameters\":" << model.parameterCount()
            << ",\"hostForwardMilliseconds\":" << elapsed << ",\"candidates\":[";
        for (size_t i=0;i<candidates.size();++i) { if(i)std::cout<<',';std::cout<<'"'<<lex.text(candidates[i].word)<<'"'; }
        std::cout << "],\"aspect\":0.449999988,\"keys\":[";
        for(size_t i=0;i<keys.size();++i) { if(i)std::cout<<',';const auto& k=keys[i];std::cout<<"[\""<<char(k.label)<<"\","<<k.cx<<','<<k.cy<<','<<k.hw<<','<<k.hh<<']'; }
        std::cout << "],\"trace\":[";
        for(size_t i=0;i<trace.size();++i) { if(i)std::cout<<',';auto p=trace[i];std::cout<<'['<<p.x<<','<<p.y<<','<<p.t<<']'; }
        std::cout<<"],\"features\":";array(input.feats);std::cout<<",\"positions\":";array(input.pos);std::cout<<",\"keyFeatures\":";array(input.keys);
        std::cout<<"}\n";
        return 0;
    } catch (const std::exception& e) { std::cerr<<e.what()<<'\n';return 1; }
}
