# Minification is off for now. When it is enabled, note that Wire's generated
# adapters are reached reflectively via their ADAPTER fields, and Nordic's
# BleManager subclasses are instantiated by name in places, so both need keep
# rules before turning R8 on.
