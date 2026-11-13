// SPDX-FileCopyrightText: Copyright 2025 suyu Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2020 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <type_traits>
#include <vector>

#include "common/polyfill_thread.h"
#include "common/thread.h"
#include "common/unique_function.h"

namespace Common {

template <class StateType = void>
class StatefulThreadWorker {
    static constexpr bool with_state = !std::is_same_v<StateType, void>;

    struct DummyCallable {
        int operator()() const noexcept {
            return 0;
        }
    };

    using Task =
        std::conditional_t<with_state, UniqueFunction<void, StateType*>, UniqueFunction<void>>;
    using StateMaker = std::conditional_t<with_state, std::function<StateType()>, DummyCallable>;

public:
    explicit StatefulThreadWorker(size_t num_workers, std::string name, StateMaker func = {})
        : workers_queued{num_workers}, active_limit{std::max<size_t>(num_workers, 1)},
          thread_name{std::move(name)} {
        const auto lambda = [this, func](std::stop_token stop_token) {
            Common::SetCurrentThreadName(thread_name.c_str());
            {
                [[maybe_unused]] std::conditional_t<with_state, StateType, int> state{func()};
                while (!stop_token.stop_requested()) {
                    Task task;
                    {
                        std::unique_lock lock{queue_mutex};
                        if (requests.empty() && pending.empty()) {
                            wait_condition.notify_all();
                        }
                        condition.wait(lock, stop_token, [this] {
                            return (!requests.empty() || !pending.empty()) &&
                                   running.load(std::memory_order::acquire) <
                                       active_limit.load(std::memory_order::acquire);
                        });
                        if (stop_token.stop_requested()) {
                            break;
                        }
                        running.fetch_add(1, std::memory_order::acq_rel);
                        if (!requests.empty()) {
                            task = std::move(requests.front());
                            requests.pop_front();
                        } else {
                            task = std::move(pending.front());
                            pending.pop_front();
                        }
                    }
                    if constexpr (with_state) {
                        task(&state);
                    } else {
                        task();
                    }
                    {
                        std::scoped_lock lock{queue_mutex};
                        ++work_done;
                        running.fetch_sub(1, std::memory_order::acq_rel);
                        condition.notify_one();
                    }
                }
            }
            ++workers_stopped;
            wait_condition.notify_all();
        };
        threads.reserve(num_workers);
        for (size_t i = 0; i < num_workers; ++i) {
            threads.emplace_back(lambda);
        }
    }

    StatefulThreadWorker& operator=(const StatefulThreadWorker&) = delete;
    StatefulThreadWorker(const StatefulThreadWorker&) = delete;

    StatefulThreadWorker& operator=(StatefulThreadWorker&&) = delete;
    StatefulThreadWorker(StatefulThreadWorker&&) = delete;

    void QueueWork(Task work) {
        Queue(std::move(work), false);
    }

    void QueueWorkFront(Task work) {
        {
            std::unique_lock lock{queue_mutex};
            requests.emplace_front(std::move(work));
            ++work_scheduled;
        }
        condition.notify_one();
    }

    void QueueWorkLow(Task work) {
        Queue(std::move(work), true);
    }

    void SetActiveThreads(size_t count) {
        active_limit.store(std::max<size_t>(count, 1), std::memory_order::release);
        condition.notify_all();
    }

    void WaitForRequests(std::stop_token stop_token = {}) {
        std::stop_callback callback(stop_token, [this] {
            for (auto& thread : threads) {
                thread.request_stop();
            }
        });
        std::unique_lock lock{queue_mutex};
        wait_condition.wait(lock, [this] {
            return workers_stopped >= workers_queued || work_done >= work_scheduled;
        });
    }

private:
    void Queue(Task work, bool low) {
        {
            std::unique_lock lock{queue_mutex};
            if (low) {
                pending.emplace_back(std::move(work));
            } else {
                requests.emplace_back(std::move(work));
            }
            ++work_scheduled;
        }
        condition.notify_one();
    }

    std::deque<Task> requests;
    std::deque<Task> pending;
    std::mutex queue_mutex;
    std::condition_variable_any condition;
    std::condition_variable wait_condition;
    std::atomic<size_t> work_scheduled{};
    std::atomic<size_t> work_done{};
    std::atomic<size_t> workers_stopped{};
    std::atomic<size_t> workers_queued{};
    std::atomic<size_t> active_limit;
    std::atomic<size_t> running{};
    std::string thread_name;
    std::vector<std::jthread> threads;
};

using ThreadWorker = StatefulThreadWorker<>;

} // namespace Common
