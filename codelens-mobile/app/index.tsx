import React, { useState, useCallback } from 'react';
import {
  StyleSheet,
  View,
  Text,
  ScrollView,
  Pressable,
  RefreshControl,
  SafeAreaView,
} from 'react-native';
import * as Haptics from 'expo-haptics';
import { TelemetrySheet } from '../components/TelemetrySheet';

export default function DashboardScreen() {
  const [refreshing, setRefreshing] = useState(false);
  const [sheetOpen, setSheetOpen] = useState(false);

  const [metrics, setMetrics] = useState({
    heapMb: 364.5,
    maxHeapMb: 2048,
    threadsCount: 19,
    deadlocksCount: 0,
    watchdogStatus: 'ARMED & MONITORING',
    indexedClasses: 2491,
    p99LatencyMs: 4.8,
  });

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
    // Simulate real-time metric update
    setTimeout(() => {
      setMetrics((prev) => ({
        ...prev,
        heapMb: 340 + Math.random() * 50,
        threadsCount: 16 + Math.floor(Math.random() * 6),
        p99LatencyMs: 3.5 + Math.random() * 3,
      }));
      setRefreshing(false);
      Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success).catch(() => {});
    }, 600);
  }, []);

  const openSheet = useCallback(() => {
    Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
    setSheetOpen(true);
  }, []);

  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView
        contentContainerStyle={styles.container}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            tintColor="#38bdf8"
          />
        }
      >
        {/* App Bar */}
        <View style={styles.header}>
          <View>
            <Text style={styles.appTitle}>CodeLens Mobile</Text>
            <Text style={styles.appSubtitle}>Engine Telemetry &amp; Diagnostics</Text>
          </View>
          <View style={styles.liveIndicator}>
            <View style={styles.liveDot} />
            <Text style={styles.liveText}>LIVE</Text>
          </View>
        </View>

        {/* Primary Health Hero Card */}
        <View style={styles.heroCard}>
          <Text style={styles.heroLabel}>ARCHITECTURE STATUS</Text>
          <Text style={styles.heroValue}>Healthy &amp; Nominal</Text>
          <Text style={styles.heroSub}>
            Zero circular package violations · Critical path stable
          </Text>
          <View style={styles.heroDivider} />
          <View style={styles.heroStatsRow}>
            <View>
              <Text style={styles.statLabel}>Indexed Types</Text>
              <Text style={styles.statValue}>{metrics.indexedClasses.toLocaleString()}</Text>
            </View>
            <View>
              <Text style={styles.statLabel}>P99 Query</Text>
              <Text style={styles.statValue}>{metrics.p99LatencyMs.toFixed(1)}ms</Text>
            </View>
            <View>
              <Text style={styles.statLabel}>Heap Usage</Text>
              <Text style={styles.statValue}>
                {((metrics.heapMb / metrics.maxHeapMb) * 100).toFixed(0)}%
              </Text>
            </View>
          </View>
        </View>

        {/* Quick Action Pressables (Emil Kowalski press scale feedback) */}
        <Text style={styles.sectionTitle}>Engine Actions</Text>
        <View style={styles.actionGrid}>
          <Pressable
            style={({ pressed }) => [
              styles.actionButton,
              pressed && styles.actionButtonPressed,
            ]}
            onPress={openSheet}
          >
            <Text style={styles.actionButtonTitle}>JVM Telemetry Sheet</Text>
            <Text style={styles.actionButtonSub}>Inspect memory watchdog &amp; heap</Text>
          </Pressable>

          <Pressable
            style={({ pressed }) => [
              styles.actionButton,
              styles.actionButtonSecondary,
              pressed && styles.actionButtonPressed,
            ]}
            onPress={() => {
              Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium).catch(() => {});
              onRefresh();
            }}
          >
            <Text style={styles.actionButtonTitle}>Synchronize Telemetry</Text>
            <Text style={styles.actionButtonSub}>Fetch latest thread &amp; AST state</Text>
          </Pressable>
        </View>
      </ScrollView>

      {/* Reanimated Bottom Sheet */}
      <TelemetrySheet
        visible={sheetOpen}
        onClose={() => setSheetOpen(false)}
        metrics={metrics}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#090d16',
  },
  container: {
    padding: 20,
    paddingBottom: 40,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 24,
  },
  appTitle: {
    fontSize: 22,
    fontWeight: '700',
    color: '#ffffff',
    letterSpacing: -0.3,
  },
  appSubtitle: {
    fontSize: 13,
    color: '#94a3b8',
    marginTop: 2,
  },
  liveIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 999,
    backgroundColor: 'rgba(16, 185, 129, 0.15)',
    borderWidth: 1,
    borderColor: 'rgba(16, 185, 129, 0.3)',
  },
  liveDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: '#10b981',
  },
  liveText: {
    fontSize: 10,
    fontWeight: '700',
    color: '#34d399',
  },
  heroCard: {
    padding: 20,
    borderRadius: 16,
    backgroundColor: '#111827',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.1)',
    marginBottom: 24,
  },
  heroLabel: {
    fontSize: 11,
    fontWeight: '700',
    color: '#38bdf8',
    letterSpacing: 0.8,
  },
  heroValue: {
    fontSize: 24,
    fontWeight: '700',
    color: '#ffffff',
    marginTop: 6,
  },
  heroSub: {
    fontSize: 13,
    color: '#94a3b8',
    marginTop: 4,
  },
  heroDivider: {
    height: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    marginVertical: 16,
  },
  heroStatsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  statLabel: {
    fontSize: 11,
    color: '#64748b',
  },
  statValue: {
    fontSize: 16,
    fontWeight: '700',
    color: '#f8fafc',
    marginTop: 2,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#94a3b8',
    textTransform: 'uppercase',
    letterSpacing: 0.6,
    marginBottom: 12,
  },
  actionGrid: {
    gap: 12,
  },
  actionButton: {
    padding: 16,
    borderRadius: 14,
    backgroundColor: '#1e293b',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.08)',
    transform: [{ scale: 1 }],
  },
  actionButtonSecondary: {
    backgroundColor: 'rgba(30, 41, 59, 0.5)',
  },
  actionButtonPressed: {
    transform: [{ scale: 0.97 }],
    opacity: 0.85,
  },
  actionButtonTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: '#ffffff',
  },
  actionButtonSub: {
    fontSize: 12,
    color: '#94a3b8',
    marginTop: 3,
  },
});
