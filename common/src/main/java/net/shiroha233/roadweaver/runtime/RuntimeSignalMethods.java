/* 文件职责：隔离旧调用键到标准负载 API 的适配，不让执行预算类再承载包装器实现。 */
package net.shiroha233.roadweaver.runtime;

// 历史类名只作兼容调用键；这些包装器始终走 API，不能重新引入反射兜底。
final class RuntimeSignalMethods {
    private RuntimeSignalMethods() {}

    static final class OptionalBooleanMethod {
        private final String className;
        private final String methodName;

        OptionalBooleanMethod(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        boolean invoke() {
            Boolean bridged = net.shiroha233.roadweaver.apiimpl.LoadSignalBridge.RuntimeSignals.booleanSignal(className, methodName);
            return Boolean.TRUE.equals(bridged);
        }
    }

    static final class OptionalBooleanOneArgMethod {
        private final String className;
        private final String methodName;

        OptionalBooleanOneArgMethod(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        void invoke(boolean value) {
            net.shiroha233.roadweaver.apiimpl.LoadSignalBridge.booleanOneArgSignal(className, methodName, value);
        }
    }

    static final class OptionalIntMethod {
        private final String className;
        private final String methodName;

        OptionalIntMethod(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        Integer invoke(int pendingRegions, int readyAheadRegions, int currentLanes) {
            int value = net.shiroha233.roadweaver.apiimpl.LoadSignalBridge.RuntimeSignals.recommendedLanes(
                    pendingRegions, readyAheadRegions, currentLanes);
            return value < 0 ? null : value;
        }
    }

    static final class OptionalIntOneArgMethod {
        private final String className;
        private final String methodName;

        OptionalIntOneArgMethod(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        Integer invoke(int value) {
            int recommendation = net.shiroha233.roadweaver.apiimpl.LoadSignalBridge.RuntimeSignals.recommendedWorkers(value);
            return recommendation < 0 ? null : recommendation;
        }
    }

    static final class OptionalVoidIntMethod {
        private final String className;
        private final String methodName;

        OptionalVoidIntMethod(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        void invoke(int value) {
            net.shiroha233.roadweaver.apiimpl.LoadSignalBridge.voidIntSignal(className, methodName, value);
        }
    }
}
