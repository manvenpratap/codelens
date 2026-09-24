import React from 'react';
import { StyleSheet, View, Text, Pressable, Dimensions } from 'react-native';
import Animated, {
  useSharedValue,
  useAnimatedStyle,
  withSpring,
  useReducedMotion,
  ReduceMotion,
} from 'react-native-reanimated';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import * as Haptics from 'expo-haptics';

const { height: SCREEN_HEIGHT } = Dimensions.get('window');
const SHEET_MAX_HEIGHT = 440;

interface TelemetrySheetProps {
  visible: boolean;
  onClose: () => void;
  metrics: {
    heapMb: number;
    maxHeapMb: number;
    threadsCount: number;
    deadlocksCount: number;
    watchdogStatus: string;
  };
}

export function TelemetrySheet({ visible, onClose, metrics }: TelemetrySheetProps) {
  const reducedMotion = useReducedMotion();
  const translateY = useSharedValue(SHEET_MAX_HEIGHT);
  const contextY = useSharedValue(0);

  React.useEffect(() => {
    if (visible) {
      Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
      translateY.set(
        withSpring(0, {
          duration: 300,
          dampingRatio: 0.8,
          reduceMotion: reducedMotion ? ReduceMotion.Always : ReduceMotion.System,
        })
      );
    } else {
      translateY.set(
        withSpring(SHEET_MAX_HEIGHT, {
          duration: 250,
          dampingRatio: 0.9,
          reduceMotion: reducedMotion ? ReduceMotion.Always : ReduceMotion.System,
        })
      );
    }
  }, [visible, reducedMotion]);

  const panGesture = Gesture.Pan()
    .onStart(() => {
      'worklet';
      contextY.set(translateY.get());
    })
    .onUpdate((event) => {
      'worklet';
      // Only permit dragging downwards to dismiss (clamping top edge)
      const nextY = contextY.get() + event.translationY;
      translateY.set(Math.max(0, nextY));
    })
    .onEnd((event) => {
      'worklet';
      const currentY = translateY.get();
      const velocity = event.velocityY;

      // Dismiss if dragged down > 120pt or flicked downwards with velocity > 600
      if (currentY > 120 || velocity > 600) {
        translateY.set(
          withSpring(
            SHEET_MAX_HEIGHT,
            {
              duration: 250,
              dampingRatio: 0.85,
              velocity: velocity,
            }
          )
        );
      } else {
        // Snap back to open position carrying velocity
        translateY.set(
          withSpring(
            0,
            {
              duration: 300,
              dampingRatio: 0.8,
              velocity: velocity,
            }
          )
        );
      }
    });

  const sheetAnimatedStyle = useAnimatedStyle(() => {
    return {
      transform: [{ translateY: translateY.get() }],
    };
  });

  const backdropAnimatedStyle = useAnimatedStyle(() => {
    const progress = 1 - Math.min(1, translateY.get() / SHEET_MAX_HEIGHT);
    return {
      opacity: progress * 0.7,
      pointerEvents: progress > 0.05 ? 'auto' : 'none',
    };
  });

  return (
    <View style={StyleSheet.absoluteFillObject} pointerEvents={visible ? 'auto' : 'none'}>
      {/* Animated Backdrop */}
      <Animated.View style={[styles.backdrop, backdropAnimatedStyle]}>
        <Pressable style={StyleSheet.absoluteFill} onPress={onClose} />
      </Animated.View>

      {/* Sheet Container with gesture detection */}
      <GestureDetector gesture={panGesture}>
        <Animated.View style={[styles.sheet, sheetAnimatedStyle]}>
          {/* Pill grab handle */}
          <View style={styles.handleContainer}>
            <View style={styles.handle} />
          </View>

          <View style={styles.header}>
            <Text style={styles.title}>JVM Telemetry & Diagnostics</Text>
            <Text style={styles.subtitle}>Real-time CodeLens Engine Metrics</Text>
          </View>

          <View style={styles.metricGrid}>
            <View style={styles.metricCard}>
              <Text style={styles.metricLabel}>HEAP ALLOCATION</Text>
              <Text style={styles.metricValue}>
                {metrics.heapMb.toFixed(1)} <Text style={styles.metricUnit}>MB</Text>
              </Text>
              <Text style={styles.metricSub}>Max: {metrics.maxHeapMb} MB</Text>
            </View>

            <View style={styles.metricCard}>
              <Text style={styles.metricLabel}>ACTIVE THREADS</Text>
              <Text style={[styles.metricValue, { color: '#34d399' }]}>
                {metrics.threadsCount}
              </Text>
              <Text style={styles.metricSub}>Contention: {metrics.deadlocksCount} deadlocks</Text>
            </View>
          </View>

          <View style={styles.statusRow}>
            <Text style={styles.statusLabel}>Watchdog Status:</Text>
            <View style={styles.statusBadge}>
              <Text style={styles.statusBadgeText}>{metrics.watchdogStatus}</Text>
            </View>
          </View>

          <Pressable
            style={({ pressed }) => [
              styles.dismissButton,
              pressed && styles.dismissButtonPressed,
            ]}
            onPress={() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
              onClose();
            }}
          >
            <Text style={styles.dismissButtonText}>Done</Text>
          </Pressable>
        </Animated.View>
      </GestureDetector>
    </View>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#000000',
  },
  sheet: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: SHEET_MAX_HEIGHT,
    backgroundColor: '#0f172a',
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.12)',
    paddingHorizontal: 20,
    paddingBottom: 32,
  },
  handleContainer: {
    width: '100%',
    alignItems: 'center',
    paddingVertical: 12,
  },
  handle: {
    width: 36,
    height: 4,
    borderRadius: 2,
    backgroundColor: 'rgba(255, 255, 255, 0.28)',
  },
  header: {
    marginBottom: 20,
  },
  title: {
    fontSize: 18,
    fontWeight: '700',
    color: '#ffffff',
    letterSpacing: -0.2,
  },
  subtitle: {
    fontSize: 12,
    color: '#94a3b8',
    marginTop: 2,
  },
  metricGrid: {
    flexDirection: 'row',
    gap: 12,
    marginBottom: 16,
  },
  metricCard: {
    flex: 1,
    padding: 14,
    borderRadius: 12,
    backgroundColor: 'rgba(30, 41, 59, 0.7)',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.06)',
  },
  metricLabel: {
    fontSize: 10,
    fontWeight: '700',
    color: '#64748b',
    letterSpacing: 0.5,
  },
  metricValue: {
    fontSize: 22,
    fontWeight: '700',
    color: '#38bdf8',
    marginTop: 4,
  },
  metricUnit: {
    fontSize: 12,
    color: '#94a3b8',
    fontWeight: '500',
  },
  metricSub: {
    fontSize: 11,
    color: '#94a3b8',
    marginTop: 2,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 12,
    paddingHorizontal: 14,
    borderRadius: 10,
    backgroundColor: 'rgba(15, 23, 42, 0.6)',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.06)',
    marginBottom: 20,
  },
  statusLabel: {
    fontSize: 13,
    color: '#cbd5e1',
  },
  statusBadge: {
    backgroundColor: 'rgba(16, 185, 129, 0.15)',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: 'rgba(16, 185, 129, 0.3)',
  },
  statusBadgeText: {
    fontSize: 11,
    fontWeight: '600',
    color: '#34d399',
  },
  dismissButton: {
    height: 48,
    borderRadius: 12,
    backgroundColor: '#0284c7',
    alignItems: 'center',
    justifyContent: 'center',
    transform: [{ scale: 1 }],
  },
  dismissButtonPressed: {
    transform: [{ scale: 0.97 }],
    opacity: 0.9,
  },
  dismissButtonText: {
    fontSize: 15,
    fontWeight: '600',
    color: '#ffffff',
  },
});
