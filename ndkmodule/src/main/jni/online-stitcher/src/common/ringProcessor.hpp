#include <thread>

#include "progressCallback.hpp"

using namespace std;

#ifndef OPTONAUT_RINGWISE_PROCESSOR_HEADER
#define OPTONAUT_RINGWISE_PROCESSOR_HEADER

namespace optonaut {
    template <typename InType>
	class RingProcessor {
	private:
        const size_t distance;
        const size_t prefixSize;
        size_t prefixCounter;

        const function<void(const InType&)> start;
        const function<void(const InType&, const InType&)> process;
        const function<void(const InType&)> finish;

        deque<InType> buffer;
        deque<InType> prefix;
        
        void PushInternal(const InType &in) {

            if(prefix.size() < prefixSize) {
                prefix.push_back(in);
            }

            buffer.push_back(in);

            if(buffer.size() > distance) {

                process(buffer.front(), buffer.back());
                
                if(prefixCounter == 0) {
                    finish(buffer.front());
                } else {
                    prefixCounter--;
                }

                buffer.pop_front();
            }
        }

    public:

        RingProcessor(size_t dist, size_t prefix,
                function<void(const InType&)> onStart,
                function<void(const InType&, const InType&)> process,
                function<void(const InType&)> onFinish) : 
            distance(dist), 
            prefixSize(prefix),
            prefixCounter(prefix),
            start(onStart),
            process(process),
            finish(onFinish) {
            //Necassary for the below implementation. 
            assert(prefixSize <= dist);
        }
        
        RingProcessor(size_t dist, 
                function<void(const InType&, const InType&)> process,
                function<void(const InType&)> onFinish) : 
            distance(dist), 
            prefixSize(dist),
            prefixCounter(dist),
            start([] (const InType &) {}),
            process(process),
            finish(onFinish) {
            //Necassary for the below implementation. 
            assert(prefixSize <= dist);
        }

        void Process(const vector<InType> &in, ProgressCallback &prog = ProgressCallback::Empty) {
            for(size_t i = 0; i < in.size(); i++) {
                Push(in[i]);
                prog((float)i / (float)in.size());
            }

            Flush();

            prog(1);
        }

        void Push(const InType &in) {
            start(in);
            PushInternal(in);
        }

        void Flush(bool push = true) {
            for(auto &pre : prefix) {
                if(push)
                    PushInternal(pre);
            }

            Clear();
        }

        void Clear() {
            for(auto &b : buffer) {
                 finish(b);
            }

            prefixCounter = prefixSize; 

            prefix.clear();
            buffer.clear();
        }
    };
}
#endif