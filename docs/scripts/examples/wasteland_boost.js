/**
 * Mujde / Frida - Wasteland Heart boost v3.7
 *
 * target : com.wasteland.heart 3.1.0 arm64
 *
 * ## Safety (from v3.6 Farmville SIGSEGV + quest soft-locks)
 *   - SyncSpeed: validate ptr + ALWAYS call original
 *   - NEVER replace ProcessTimer.StartTimer / SyncCraftTimer (float+bool ABI)
 *   - No ShowMeHow / get_ReqLvl hooks
 *   - Attribute / brush / craft-float hooks only after warmUpMs
 *   - Soft dig/vacuum (do not nuke quest pickups)
 *   - Hold completes call game Complete* / Stop after original start path
 *   - No infinite-fuel / save-key inventing
 *
 * ## v3.7 adds
 *   1) Building/craft float timers + building/craft rate attrs (after warm-up)
 *   2) Flamethrower level bypass (vine level/lock) + damage attr / SetDamage
 *   3) Instant put-out / lattice cut / ice melt (after warm-up)
 *
 * Mujde: bind script -> force-stop game -> cold start
 * logcat: adb logcat -s WL_BOOST:I
 */

'use strict';

var FLAG = '__WL_BOOST_V311__';
var TAG = 'WL_BOOST';

var CFG = {
  // player move via SyncSpeed only (do NOT hook MovementEngine.SetSpeed -
  // that breaks villager NavMesh / escort AI)
  moveSpeed: 16.0,
  pickaxeDamage: 250.0,
  pickaxeAnimMul: 3.0,
  pickaxeRange: 5.0,
  vacuumAreaMul: 1.8,
  vacuumSolidMul: 3.0,
  actionDuration: 1.0,

  ignorePickLevel: true,
  ignoreVacuumLevel: true,

  // float CalculateTimer path only; installed AFTER warm-up
  speedCraftTimers: true,
  buildingRateMul: 10.0,

  flameDamage: 800.0,
  flameLevel: 99,
  flameTickDamage: 500,
  flameTickTime: 0.05,

  // Escort v3.11: ONE villager at a time (skip-rescued order).
  // Branch2 "Upgrade pickaxe" steals quest UI — block + force-complete it.
  // Quest progress is OnSaveNpc (not BikeQueue flags).
  followIgnoreSick: true,
  walkToBike: true,
  sendVillagersToBike: true,
  blockFollowChain: false,
  bikePrefer: 'queue',
  escortActions: [1], // only active FollowPlayer walks
  forceFollowPlayer: true,
  escortAnyNotInVillage: false, // do NOT yank all skip-rescued at once
  escortOneAtATime: true,
  bikeNavIntervalMs: 1500,
  bikeArriveDist: 3.5,
  softActivateBikeForEscort: true,
  handsOffNearQueue: true,
  softCompleteBike: false,
  stuckNearMs: 12000,
  forceSaveNpcNearBike: true,
  forceOnSaveNpcComplete: true,
  killPickaxeQuestSteal: true,
  forceCompleteAfterRescue: false,
  forceCompleteCurrentQuest: false,
  forceCompleteAfterEndPath: false,
  forceCompleteEndPathWaitMs: 12000,
  recoverStuckOnBike: false,
  recoverCamera: true,
  disableBikeActivateCalls: false,
  blockRideCutscene: true,

  instantPutOut: true,
  instantLattice: true,
  instantIce: true,

  warmUpMs: 8000,
  logEvery: 40
};

var ATTR = {
  MovementSpeed: 6,
  DiggingSand_MovementSpeed: 1606,
  AnimationSpeed: 1711,
  ExtractionSpeed: 5454,
  ExtractionDamage: 4215,
  Weapon_VG_Power: 951,
  Weapon_VG_MoveSpeed: 958,
  FlamethrowerDamage: 4900,
  FlamethrowerMovementSpeed: 5000,
  Upgrade_Tool_Rate: 2000,
  Workshop_Craft_Rate: 2002,
  Laboratory_Craft_Rate: 2003,
  Tech_Craft_Rate: 2004,
  buff_BuildingRate: 2005
};

var RVA = {
  Registrate: 0x22c0884,
  Find: 0x22bd088,
  set_baseValue: 0x22bd288,
  set_additive: 0x22bd2e0,
  Recalculate: 0x22bd8b0,
  SyncSpeed_UpdateValue: 0x254eb48,
  SyncExtractionSpeed_UpdateValue: 0x254e844,
  CalcMoveParams: 0x2556d88,
  BrushSolid_DoWork: 0x2558188,
  IsResourceLockedByItem: 0x2549a40,
  IsLevelPassed: 0x27068c0,
  IsResourceReqPassed_Eq: 0x2706990,
  IsResourceReqPassed_Inv: 0x2706a70,
  IsReqPassed_Slot: 0x2706a48,
  HasRequiredTool_Player: 0x2557458,
  HasRequiredTool_Worker: 0x23a30f8,
  HasRequiredToolInHand: 0x23a32c0,
  SandPlaneValidationCallback_Player: 0x25582d0,
  SandPlaneValidationCallback_Worker: 0x23a3008,
  SandPlaneValidation_Brush: 0x255b510,
  SetInteractRange: 0x254a64c,
  Extraction_SetAttributes: 0x25496e8,
  WaterPump_UpdatePumping: 0x22ed28c,
  ResourcesFactory_GetCraftDuration: 0x24d9da0,
  ResourcesFactory_SetCraftTime: 0x24db2f8,
  ResourcesFactory_SetCraftPerSecond: 0x24d9a9c,
  UpgradeRecipeTimerConfig_CalculateTimer: 0x24cb5fc,
  UpgradeRecipeTimerConfig_CalculateTimerVIP: 0x24cb64c,
  RecipeSlotInfo_CalculateTimer: 0x2401bf0,
  RecipeSlotInfo_SetTimer: 0x2401f20,

  VineGroup_get_Level: 0x234df24,
  VineGroupCollider_get_Level: 0x22deee4,
  VineGroupCollider_get_IsLock: 0x22def00,
  Flamethrower_GetLevel: 0x24108e0,
  Flamethrower_ShowPopup: 0x2412638,
  VineDamage_SetDamage: 0x2352598,

  PutOut_AddCycle: 0x239bb14,
  PutOut_Stop: 0x239bb84,
  Lattice_PlayAnimation: 0x248258c,
  Lattice_CompleteBreak: 0x24829a8,
  Ice_StartMelt: 0x22dc2f4,
  Ice_CompleteMelt: 0x22dc504,

  VillagerState_get_IsSick: 0x2321f84,
  VillagerState_SetSickState: 0x23233dc,
  VillagerState_SetActivate: 0x2322eb8,
  VillagerState_SetActionType: 0x2323568,
  VillagerState_Update: 0x2323058,
  GraphOwner_StartBehaviour: 0x439acc4,
  GraphOwner_StopBehaviour: 0x439aefc,
  BikeQueue_get_QueuePoint: 0x2484f1c,
  BikeQueue_get_LookPoint: 0x2484f24,
  BikeQueue_get_Parent: 0x2484f14,
  BikeQueue_IncreaseVillagersCount: 0x2484fcc,
  BikeQueue_SetIsVillagerEndPathToBike: 0x2484fdc,
  BikeQueue_SetIsVillagerEndPath: 0x2484f6c,
  BikeQueue_SetActivate: 0x2485000,
  BikeQueue_PlayAnimation: 0x248500c,
  BikeQueue_SetDisablePanel: 0x2484f80,
  IsBikeQueueActivate_OnCheck: 0x23358ec,
  FindBikeQueuePoint_OnExecute: 0x23383c4,
  PlayerBikeEnter_Awake: 0x2459078,
  PlayerBikeEnter_OnPlay: 0x2459274,
  PlayerBikeEnter_OnStop: 0x2459964,
  PlayerBikeEnter_Update: 0x24591f0,
  PlayerBikeExit_OnStop: 0x245a270,
  PlayerBikeExit_OnPlay: 0x2459db4,
  BaseCutscene_SetCameraTargetToPlayer: 0x245400c,
  PlayerCameraPoint_ApplyCamera: 0x255a590,
  PlayerCameraPoint_SetTarget: 0x256870c,
  PlayerCameraPoint_Update: 0x2568778,
  PlayerCameraPoint_Awake: 0x25685e0,
  CameraHandler_GetInstance: 0x2605804,
  TransformFollower_SetDisableSetTarget: 0x262bcc0,
  TransformFollower_SetTarget_Simple: 0x262bccc,
  TransformFollower_ForceUpdate: 0x262be20,
  CameraStatic_StopTutorCamera: 0x24cdba0,
  BikeQueue_OnExitCollider: 0x2485098,
  BikeQueue_ctor: 0x24850c0,
  FindBikeQueue_OnExecute: 0x23392f0,
  SetNpcToBike_OnExecute: 0x2338a54,
  World_get_actualWorld: 0x249d214,
  World_get_BikeQueue: 0x249d6b8,
  World_get_villagers: 0x249d93c,
  Component_get_transform: 0x4c80660,
  Component_get_gameObject: 0x4c8069c,
  GameObject_SetActive: 0x4c847d8,
  Behaviour_set_enabled: 0x4c7fb6c,
  Transform_SetParent: 0x4c90060,
  FollowChain_OnUpdate: 0x23314d0,
  FollowChain_OnExecute: 0x233136c,
  Transform_get_position_Injected: 0x4c8f330,
  NavMesh_SetDestination_Injected: 0x4c0a240,
  NavMesh_Warp_Injected: 0x4c0af14,
  NavMesh_set_isStopped: 0x4c0b0e0,
  NavMesh_get_remainingDistance: 0x4c0a7f4,
  ForceCompleteCurrentQuest: 0x2575f40,
  GetCurrentValidator: 0x2576320,
  ConditionValidator_get_IsDone: 0x25aa23c,
  ConditionValidator_get_Progress: 0x25adfbc,
  ConditionValidator_Debug_ForceComplete: 0x25ae618,
  OnSaveNpc_InitCondition: 0x23006f4,
  OnSaveNpc_ForceCompleteCondition: 0x23014f8,
  OnSaveNpc_ForceSaveNpc: 0x2301648,
  OnSaveNpc_TrackProgress: 0x230117c,
  VillagerState_get_IsInVillage: 0x232221c,
  QuestBranch_CreateActivatedQuest: 0x43ef810,
  QuestBranch_ActivateQuest: 0x43f0388,
  QuestBranch_GetActivatedQuestName: 0x43ee340,
  QuestBranch_GetQuestName: 0x43ee050,
  QuestBranch_GetActiveQuest: 0x43f0cd8,
  CheckEquipmentLevel_InitCondition: 0x22fec08,
  UIQuestInfo_SetQuestInfo: 0x43f5a68,
  UIQuestInfo_ContentUpdate: 0x43f274c,
  ShowQuestPanel_OnStartAction: 0x43f42c4
};

var BV = { base: 0x10, additive: 0x14, mult: 0x18, value: 0x20, clean: 0x24, dirty: 0x32 };

var tracked = Object.create(null);
var counters = Object.create(null);
var brushBase = Object.create(null);
var nfRecalc = null;
var applying = false;
var warmedUp = false;
var holdBusy = false;
var craftInstalled = false;
var questForceDone = false;
var followNavAt = Object.create(null);
var lastBikeQueue = null;
var bikePointsLogged = false;
var lastPlayerTr = null;
var lastPlayerComp = null;
var lastBikeEnterCutscene = null;
var lastPlayerCameraPoint = null;
var lastOnSaveNpc = null;
var cameraRecoverDone = false;
var onSaveNpcForced = false;
var trackedVillagers = [];
var endPathToBikeSeen = false;
var endPathForceScheduled = false;
var activeEscortKey = null;
var pickaxeStealKilled = false;
var RAD_MUL = Math.sqrt(CFG.vacuumAreaMul);

function alog(msg) {
  var line = '[' + TAG + '] ' + msg;
  console.log(line);
  try {
    var exp =
      Module.findExportByName('liblog.so', '__android_log_write') ||
      Module.getExportByName('liblog.so', '__android_log_write');
    var fn = new NativeFunction(exp, 'int', ['int', 'pointer', 'pointer']);
    fn(4, Memory.allocUtf8String(TAG), Memory.allocUtf8String(line));
  } catch (e) {}
  try {
    var f = new File('/sdcard/Download/wl_boost_runtime.log', 'a');
    f.write(line + '\n');
    f.close();
  } catch (e2) {}
}

function waitModule(name, cb) {
  var m = Process.findModuleByName(name);
  if (m) {
    cb(m);
    return;
  }
  setTimeout(function () {
    waitModule(name, cb);
  }, 300);
}

function bump(key) {
  counters[key] = (counters[key] || 0) + 1;
  return counters[key];
}

function shouldLog(key) {
  var n = bump(key);
  return n <= 3 || n % CFG.logEvery === 0;
}

function isSanePtr(p) {
  try {
    if (!p || p.isNull()) return false;
    if (p.compare(ptr('0x100000')) < 0) return false;
    return true;
  } catch (e) {
    return false;
  }
}

function attrName(attr) {
  if (attr === ATTR.ExtractionDamage) return 'PickDmg';
  if (attr === ATTR.ExtractionSpeed) return 'ExtractSpd';
  if (attr === ATTR.AnimationSpeed) return 'Anim';
  if (attr === ATTR.Weapon_VG_Power) return 'VgPower';
  if (attr === ATTR.Weapon_VG_MoveSpeed) return 'VgMove';
  if (attr === ATTR.MovementSpeed) return 'Move';
  if (attr === ATTR.DiggingSand_MovementSpeed) return 'DigMove';
  if (attr === ATTR.FlamethrowerDamage) return 'FlameDmg';
  if (attr === ATTR.FlamethrowerMovementSpeed) return 'FlameMove';
  if (attr === ATTR.buff_BuildingRate) return 'BuildRate';
  if (attr === ATTR.Workshop_Craft_Rate) return 'WorkshopRate';
  if (attr === ATTR.Laboratory_Craft_Rate) return 'LabRate';
  if (attr === ATTR.Tech_Craft_Rate) return 'TechRate';
  if (attr === ATTR.Upgrade_Tool_Rate) return 'ToolRate';
  return 'A' + attr;
}

function isMoveAttr(attr) {
  return (
    attr === ATTR.MovementSpeed ||
    attr === ATTR.DiggingSand_MovementSpeed ||
    attr === ATTR.Weapon_VG_MoveSpeed ||
    attr === ATTR.FlamethrowerMovementSpeed
  );
}

function forceBvValue(bv, value, name, ignoreWarmUp) {
  if (!ignoreWarmUp && !warmedUp) return;
  if (!isSanePtr(bv)) return;
  try {
    applying = true;
    bv.add(BV.base).writeFloat(value);
    bv.add(BV.additive).writeFloat(0);
    bv.add(BV.mult).writeFloat(1);
    bv.add(BV.value).writeFloat(value);
    bv.add(BV.clean).writeFloat(value);
    try {
      bv.add(BV.dirty).writeU8(0);
    } catch (e0) {}
    if (nfRecalc) {
      try {
        nfRecalc(bv);
      } catch (e1) {}
      bv.add(BV.value).writeFloat(value);
      bv.add(BV.base).writeFloat(value);
    }
    if (shouldLog('force:' + name)) alog('force ' + name + '=' + value);
  } catch (e) {
    if (shouldLog('forceFail')) alog('forceBv fail ' + name + ': ' + e);
  } finally {
    applying = false;
  }
}

function mulRateAttr(bv, name) {
  try {
    var base = bv.add(BV.base).readFloat();
    if (!(base > 0)) base = 1;
    forceBvValue(bv, base * CFG.buildingRateMul, name);
  } catch (e) {
    forceBvValue(bv, CFG.buildingRateMul, name);
  }
}

function onAttr(attr, bv) {
  if (!isSanePtr(bv)) return;
  var name = attrName(attr);
  // always track; move attrs apply immediately, others wait warm-up
  tracked[bv.toString()] = { attr: attr, name: name };

  if (isMoveAttr(attr)) {
    forceBvValue(bv, CFG.moveSpeed, name, true);
    return;
  }
  if (!warmedUp) return;

  if (attr === ATTR.ExtractionDamage) {
    forceBvValue(bv, CFG.pickaxeDamage, name);
    return;
  }
  if (attr === ATTR.ExtractionSpeed || attr === ATTR.AnimationSpeed) {
    try {
      var base = bv.add(BV.base).readFloat();
      if (!(base > 0)) base = 1;
      forceBvValue(bv, base * CFG.pickaxeAnimMul, name);
    } catch (e) {
      forceBvValue(bv, CFG.pickaxeAnimMul, name);
    }
    return;
  }
  if (attr === ATTR.FlamethrowerDamage) {
    forceBvValue(bv, CFG.flameDamage, name);
    return;
  }
  if (
    attr === ATTR.buff_BuildingRate ||
    attr === ATTR.Workshop_Craft_Rate ||
    attr === ATTR.Laboratory_Craft_Rate ||
    attr === ATTR.Tech_Craft_Rate ||
    attr === ATTR.Upgrade_Tool_Rate
  ) {
    mulRateAttr(bv, name);
  }
}

function forceWaterPumpExtractionTimes(pump) {
  if (!warmedUp || !isSanePtr(pump)) return;
  try {
    var config = pump.add(0x20).readPointer();
    if (!isSanePtr(config)) return;
    var list = config.add(0x18).readPointer();
    if (!isSanePtr(list)) return;
    var items = list.add(0x10).readPointer();
    var size = list.add(0x18).readS32();
    if (!isSanePtr(items) || size <= 0 || size > 64) return;
    for (var i = 0; i < size; i++) {
      var lvl = items.add(0x20 + i * Process.pointerSize).readPointer();
      if (!isSanePtr(lvl)) continue;
      var old = lvl.add(0x10).readFloat();
      if (old !== CFG.actionDuration) lvl.add(0x10).writeFloat(CFG.actionDuration);
    }
  } catch (e) {}
}

function installCraftTimers(il2cpp) {
  if (craftInstalled || !CFG.speedCraftTimers) return;
  craftInstalled = true;

  Interceptor.attach(il2cpp.base.add(RVA.WaterPump_UpdatePumping), {
    onEnter: function (args) {
      forceWaterPumpExtractionTimes(args[0]);
    }
  });

  Interceptor.replace(
    il2cpp.base.add(RVA.ResourcesFactory_GetCraftDuration),
    new NativeCallback(
      function (thiz) {
        return CFG.actionDuration;
      },
      'float',
      ['pointer']
    )
  );
  var origSetCraftTime = new NativeFunction(
    il2cpp.base.add(RVA.ResourcesFactory_SetCraftTime),
    'void',
    ['pointer', 'float']
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.ResourcesFactory_SetCraftTime),
    new NativeCallback(
      function (thiz, v) {
        return origSetCraftTime(thiz, CFG.actionDuration);
      },
      'void',
      ['pointer', 'float']
    )
  );
  var origSetCraftPerSec = new NativeFunction(
    il2cpp.base.add(RVA.ResourcesFactory_SetCraftPerSecond),
    'void',
    ['pointer', 'float']
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.ResourcesFactory_SetCraftPerSecond),
    new NativeCallback(
      function (thiz, v) {
        return origSetCraftPerSec(thiz, 1.0 / CFG.actionDuration);
      },
      'void',
      ['pointer', 'float']
    )
  );

  Interceptor.replace(
    il2cpp.base.add(RVA.UpgradeRecipeTimerConfig_CalculateTimer),
    new NativeCallback(
      function (thiz) {
        return CFG.actionDuration;
      },
      'float',
      ['pointer']
    )
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.UpgradeRecipeTimerConfig_CalculateTimerVIP),
    new NativeCallback(
      function (thiz) {
        return CFG.actionDuration;
      },
      'float',
      ['pointer']
    )
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.RecipeSlotInfo_CalculateTimer),
    new NativeCallback(
      function (thiz) {
        return CFG.actionDuration;
      },
      'float',
      ['pointer']
    )
  );
  var origSlotSetTimer = new NativeFunction(
    il2cpp.base.add(RVA.RecipeSlotInfo_SetTimer),
    'void',
    ['pointer', 'float']
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.RecipeSlotInfo_SetTimer),
    new NativeCallback(
      function (thiz, seconds) {
        return origSlotSetTimer(thiz, CFG.actionDuration);
      },
      'void',
      ['pointer', 'float']
    )
  );
  alog('craft float timers ON after warm-up (ProcessTimer NOT hooked)');
}

function callHoldComplete(fn, thiz, tag) {
  if (!warmedUp || holdBusy) return;
  if (!isSanePtr(thiz)) return;
  holdBusy = true;
  try {
    fn(thiz);
    if (shouldLog(tag)) alog(tag + ' complete');
  } catch (e) {
    if (shouldLog(tag + 'Fail')) alog(tag + ' fail: ' + e);
  } finally {
    holdBusy = false;
  }
}

function install(il2cpp) {
  alog('libil2cpp base=' + il2cpp.base);
  alog(
    'v3.11 escort oneAtATime=' +
      (CFG.escortOneAtATime ? 1 : 0) +
      ' killPickaxe=' +
      (CFG.killPickaxeQuestSteal ? 1 : 0) +
      ' warmMs=' +
      CFG.warmUpMs
  );

  setTimeout(function () {
    warmedUp = true;
    alog('warm-up done - attr/brush/craft/hold enabled');
    try {
      installCraftTimers(il2cpp);
    } catch (e) {
      alog('craft install fail: ' + e);
    }
  }, CFG.warmUpMs);

  nfRecalc = new NativeFunction(il2cpp.base.add(RVA.Recalculate), 'void', ['pointer']);

  Interceptor.attach(il2cpp.base.add(RVA.Registrate), {
    onEnter: function (args) {
      this.attr = args[1].toInt32();
      this.bv = args[2];
    },
    onLeave: function () {
      onAttr(this.attr, this.bv);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.Find), {
    onEnter: function (args) {
      this.attr = args[1].toInt32();
    },
    onLeave: function (retval) {
      onAttr(this.attr, retval);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.set_baseValue), {
    onEnter: function (args) {
      this.meta = tracked[args[0].toString()] || null;
      this.bv = args[0];
    },
    onLeave: function () {
      if (applying || !this.meta) return;
      onAttr(this.meta.attr, this.bv);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.set_additive), {
    onEnter: function (args) {
      this.meta = tracked[args[0].toString()] || null;
      this.bv = args[0];
    },
    onLeave: function () {
      if (applying || !this.meta) return;
      onAttr(this.meta.attr, this.bv);
    }
  });

  // ---- SyncSpeed: call original only; no MovementEngine.SetSpeed (breaks NPC escort) ----
  var origSyncSpeed = new NativeFunction(
    il2cpp.base.add(RVA.SyncSpeed_UpdateValue),
    'void',
    ['pointer', 'float']
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.SyncSpeed_UpdateValue),
    new NativeCallback(
      function (thiz, v) {
        // Only boost player-like cruise values; leave tiny/NPC sync alone
        var nv = v;
        if (v >= 2.5 && v <= 30.0) {
          nv = CFG.moveSpeed;
          lastPlayerComp = thiz; // player cruise — use as ride-ring fallback
        }
        if (shouldLog('SyncSpeed')) {
          alog('SyncSpeed ' + v.toFixed(2) + ' -> ' + nv.toFixed(2));
        }
        return origSyncSpeed(thiz, nv);
      },
      'void',
      ['pointer', 'float']
    )
  );
  alog('move boost: SyncSpeed only -> ' + CFG.moveSpeed + ' (no SetSpeed hook)');

  var nfSetSick = new NativeFunction(
    il2cpp.base.add(RVA.VillagerState_SetSickState),
    'void',
    ['pointer', 'bool']
  );
  var nfSetActivate = new NativeFunction(
    il2cpp.base.add(RVA.VillagerState_SetActivate),
    'void',
    ['pointer', 'bool']
  );
  var nfSetActionType = new NativeFunction(
    il2cpp.base.add(RVA.VillagerState_SetActionType),
    'void',
    ['pointer', 'int']
  );
  var nfOnSaveForceVillager = new NativeFunction(
    il2cpp.base.add(RVA.OnSaveNpc_ForceSaveNpc),
    'void',
    ['pointer', 'pointer']
  );
  var nfOnSaveForceComplete = new NativeFunction(
    il2cpp.base.add(RVA.OnSaveNpc_ForceCompleteCondition),
    'void',
    ['pointer']
  );
  var nfValidatorDebugForce = new NativeFunction(
    il2cpp.base.add(RVA.ConditionValidator_Debug_ForceComplete),
    'void',
    ['pointer']
  );
  var nfStartBt = new NativeFunction(
    il2cpp.base.add(RVA.GraphOwner_StartBehaviour),
    'void',
    ['pointer']
  );
  var nfGetQueuePoint = new NativeFunction(
    il2cpp.base.add(RVA.BikeQueue_get_QueuePoint),
    'pointer',
    ['pointer']
  );
  var nfGetLookPoint = new NativeFunction(
    il2cpp.base.add(RVA.BikeQueue_get_LookPoint),
    'pointer',
    ['pointer']
  );
  var nfGetParent = new NativeFunction(
    il2cpp.base.add(RVA.BikeQueue_get_Parent),
    'pointer',
    ['pointer']
  );
  var nfBikeSetActivate = new NativeFunction(
    il2cpp.base.add(RVA.BikeQueue_SetActivate),
    'void',
    ['pointer', 'bool']
  );
  var nfBikeDisablePanel = new NativeFunction(
    il2cpp.base.add(RVA.BikeQueue_SetDisablePanel),
    'void',
    ['pointer', 'bool']
  );
  var nfBikeIncrease = new NativeFunction(
    il2cpp.base.add(RVA.BikeQueue_IncreaseVillagersCount),
    'void',
    ['pointer']
  );
  var nfBikeEndPathToBike = new NativeFunction(
    il2cpp.base.add(RVA.BikeQueue_SetIsVillagerEndPathToBike),
    'void',
    ['pointer', 'bool']
  );
  var nfBikeEndPath = new NativeFunction(
    il2cpp.base.add(RVA.BikeQueue_SetIsVillagerEndPath),
    'void',
    ['pointer', 'bool']
  );
  var nfBikeEnterOnStop = new NativeFunction(
    il2cpp.base.add(RVA.PlayerBikeEnter_OnStop),
    'void',
    ['pointer']
  );
  var nfSetCamToPlayer = new NativeFunction(
    il2cpp.base.add(RVA.BaseCutscene_SetCameraTargetToPlayer),
    'void',
    ['pointer']
  );
  var nfPlayerCamApply = new NativeFunction(
    il2cpp.base.add(RVA.PlayerCameraPoint_ApplyCamera),
    'void',
    ['pointer', 'int', 'float']
  );
  var nfPlayerCamSetTarget = new NativeFunction(
    il2cpp.base.add(RVA.PlayerCameraPoint_SetTarget),
    'void',
    ['pointer']
  );
  var nfCamHandlerGet = new NativeFunction(
    il2cpp.base.add(RVA.CameraHandler_GetInstance),
    'pointer',
    []
  );
  var nfFollowerDisable = new NativeFunction(
    il2cpp.base.add(RVA.TransformFollower_SetDisableSetTarget),
    'void',
    ['pointer', 'bool']
  );
  var nfFollowerForce = new NativeFunction(
    il2cpp.base.add(RVA.TransformFollower_ForceUpdate),
    'void',
    ['pointer', 'int', 'int', 'int']
  );
  var nfStopTutorCam = new NativeFunction(
    il2cpp.base.add(RVA.CameraStatic_StopTutorCamera),
    'void',
    ['bool', 'bool']
  );
  var nfCompGameObject = new NativeFunction(
    il2cpp.base.add(RVA.Component_get_gameObject),
    'pointer',
    ['pointer']
  );
  var nfGoSetActive = new NativeFunction(
    il2cpp.base.add(RVA.GameObject_SetActive),
    'void',
    ['pointer', 'bool']
  );
  var nfSetParent = new NativeFunction(
    il2cpp.base.add(RVA.Transform_SetParent),
    'void',
    ['pointer', 'pointer', 'bool']
  );
  var nfWorldActual = new NativeFunction(
    il2cpp.base.add(RVA.World_get_actualWorld),
    'pointer',
    []
  );
  var nfWorldBike = new NativeFunction(
    il2cpp.base.add(RVA.World_get_BikeQueue),
    'pointer',
    ['pointer']
  );
  var nfWorldVillagers = new NativeFunction(
    il2cpp.base.add(RVA.World_get_villagers),
    'pointer',
    ['pointer']
  );
  var nfGetPos = new NativeFunction(
    il2cpp.base.add(RVA.Transform_get_position_Injected),
    'void',
    ['pointer', 'pointer']
  );
  var nfCompTransform = new NativeFunction(
    il2cpp.base.add(RVA.Component_get_transform),
    'pointer',
    ['pointer']
  );
  var nfBehEnabled = new NativeFunction(
    il2cpp.base.add(RVA.Behaviour_set_enabled),
    'void',
    ['pointer', 'bool']
  );
  var nfSetDest = new NativeFunction(
    il2cpp.base.add(RVA.NavMesh_SetDestination_Injected),
    'bool',
    ['pointer', 'pointer']
  );
  var nfSetStopped = new NativeFunction(
    il2cpp.base.add(RVA.NavMesh_set_isStopped),
    'void',
    ['pointer', 'bool']
  );
  var nfRemainDist = new NativeFunction(
    il2cpp.base.add(RVA.NavMesh_get_remainingDistance),
    'float',
    ['pointer']
  );
  var nfForceQuest = new NativeFunction(
    il2cpp.base.add(RVA.ForceCompleteCurrentQuest),
    'void',
    []
  );
  var nfGetValidator = new NativeFunction(
    il2cpp.base.add(RVA.GetCurrentValidator),
    'pointer',
    []
  );
  var nfValidatorIsDone = new NativeFunction(
    il2cpp.base.add(RVA.ConditionValidator_get_IsDone),
    'bool',
    ['pointer']
  );
  var nfValidatorProgress = new NativeFunction(
    il2cpp.base.add(RVA.ConditionValidator_get_Progress),
    'float',
    ['pointer']
  );

  function rememberBike(q) {
    if (isSanePtr(q)) {
      if (!lastBikeQueue || lastBikeQueue.toString() !== q.toString()) {
        bikePointsLogged = false;
      }
      lastBikeQueue = q;
      logBikePointsOnce();
    }
  }

  function refreshPlayerTransform() {
    var comp = lastPlayerComp;
    if (!isSanePtr(comp)) return;
    try {
      var tr = nfCompTransform(comp);
      if (isSanePtr(tr) && posOfTransform(tr)) lastPlayerTr = tr;
    } catch (e) {}
  }

  function trackVillager(v) {
    if (!isSanePtr(v)) return;
    var key = v.toString();
    if (followNavAt[key + ':trk']) return;
    followNavAt[key + ':trk'] = 1;
    trackedVillagers.push(v);
    if (shouldLog('villTrack')) {
      try {
        alog('track villager action=' + v.add(0x24).readS32());
      } catch (e) {
        alog('track villager');
      }
    }
  }

  function isEscortAction(a) {
    var list = CFG.escortActions || [1];
    for (var i = 0; i < list.length; i++) {
      if (list[i] === a) return true;
    }
    return false;
  }

  function readIl2CppString(p) {
    if (!isSanePtr(p)) return '';
    try {
      var len = p.add(0x10).readS32();
      if (len <= 0 || len > 512) return '';
      return p.add(0x14).readUtf16String(len) || '';
    } catch (e) {
      return '';
    }
  }

  function isPickaxeQuestName(name) {
    if (!name) return false;
    var s = String(name).toLowerCase();
    return s.indexOf('pickaxe') >= 0 || s.indexOf('upgrade pick') >= 0 || name.indexOf('镐') >= 0;
  }

  function isBringBikeQuestName(name) {
    if (!name) return false;
    var s = String(name).toLowerCase();
    return (
      s.indexOf('bike') >= 0 ||
      s.indexOf('bring') >= 0 ||
      s.indexOf('npc') >= 0 ||
      name.indexOf('摩托') >= 0 ||
      name.indexOf('村民') >= 0
    );
  }

  function distToBikeApprox(v) {
    try {
      var tr = nfCompTransform(v);
      var p = posOfTransform(tr);
      if (!p) return 9999;
      var tgt = getWalkTargetTransform();
      if (tgt && tgt.p) {
        var dx = p.x - tgt.p.x;
        var dz = p.z - tgt.p.z;
        return Math.sqrt(dx * dx + dz * dz);
      }
      // fallback: known QueuePoint cluster
      var dx2 = p.x - 1.0;
      var dz2 = p.z - 20.5;
      return Math.sqrt(dx2 * dx2 + dz2 * dz2);
    } catch (e) {
      return 9999;
    }
  }

  function isEscortCandidate(v) {
    if (!isSanePtr(v)) return false;
    try {
      var inVill = v.add(0xc8).readU8();
      if (inVill) return false;
      if (CFG.escortAnyNotInVillage) return true;
      return isEscortAction(v.add(0x24).readS32());
    } catch (e) {
      return false;
    }
  }

  function demoteOtherFollowers(keep) {
    if (!CFG.escortOneAtATime || !isSanePtr(keep)) return;
    var keepKey = keep.toString();
    for (var i = 0; i < trackedVillagers.length; i++) {
      var v = trackedVillagers[i];
      if (!isSanePtr(v) || v.toString() === keepKey) continue;
      try {
        if (v.add(0xc8).readU8()) continue;
        if (followNavAt[v.toString() + ':saved']) continue;
        if (v.add(0x24).readS32() === 1) {
          nfSetActionType(v, 0); // NeedHelp — wait turn (skip-rescued order)
        }
      } catch (e) {}
    }
  }

  function pickActiveEscort() {
    if (!CFG.escortOneAtATime) return null;
    if (activeEscortKey && followNavAt[activeEscortKey + ':saved']) {
      activeEscortKey = null;
    }
    if (activeEscortKey) {
      for (var i = 0; i < trackedVillagers.length; i++) {
        var v0 = trackedVillagers[i];
        if (isSanePtr(v0) && v0.toString() === activeEscortKey && isEscortCandidate(v0)) {
          return v0;
        }
      }
      activeEscortKey = null;
    }
    var best = null;
    var bestD = 1e9;
    for (var j = 0; j < trackedVillagers.length; j++) {
      var v = trackedVillagers[j];
      if (!isSanePtr(v) || !isEscortCandidate(v)) continue;
      var key = v.toString();
      if (followNavAt[key + ':saved']) continue;
      var d = distToBikeApprox(v);
      if (d < bestD) {
        bestD = d;
        best = v;
      }
    }
    if (best) {
      activeEscortKey = best.toString();
      demoteOtherFollowers(best);
      alog('activeEscort pick d=' + bestD.toFixed(1) + ' ' + activeEscortKey.slice(-8));
    }
    return best;
  }

  function promoteNextEscortFromNeedHelp() {
    if (!CFG.escortOneAtATime) return;
    var best = null;
    var bestD = 1e9;
    for (var i = 0; i < trackedVillagers.length; i++) {
      var v = trackedVillagers[i];
      if (!isSanePtr(v)) continue;
      try {
        if (v.add(0xc8).readU8()) continue;
        var act = v.add(0x24).readS32();
        var key = v.toString();
        if (followNavAt[key + ':saved']) continue;
        if (act !== 0 && act !== 1) continue;
        if (act === 1 && key === activeEscortKey) continue;
        var d = distToBikeApprox(v);
        // prefer NeedHelp (0) waiting their turn; among them closest to bike
        var score = d + (act === 0 ? 0 : 1000);
        if (score < bestD) {
          bestD = score;
          best = v;
        }
      } catch (e) {}
    }
    if (!best) return;
    try {
      nfSetActionType(best, 1);
      nfSetSick(best, 0);
      best.add(0x5a).writeU8(0);
      activeEscortKey = best.toString();
      alog('promote next FollowPlayer ' + activeEscortKey.slice(-8));
    } catch (e) {}
  }

  function ensureFollowPlayer(v) {
    if (!CFG.forceFollowPlayer || !isSanePtr(v)) return;
    try {
      var act = v.add(0x24).readS32();
      if (act !== 1) {
        nfSetActionType(v, 1);
        alog('force SetActionType=FollowPlayer was=' + act);
      }
      nfSetSick(v, 0);
      v.add(0x5a).writeU8(0);
    } catch (e) {}
  }

  function tryForceSaveNpcNear(v) {
    if (!CFG.forceSaveNpcNearBike || onSaveNpcForced) return;
    if (!isSanePtr(lastOnSaveNpc) || !isSanePtr(v)) return;
    var key = v.toString();
    if (followNavAt[key + ':saved']) return;
    followNavAt[key + ':saved'] = 1;
    try {
      nfOnSaveForceVillager(lastOnSaveNpc, v);
      alog('OnSaveNpc.ForceSaveNpc ' + key.slice(-8));
      if (CFG.escortOneAtATime) {
        activeEscortKey = null;
        setTimeout(promoteNextEscortFromNeedHelp, 400);
      }
    } catch (e) {
      alog('ForceSaveNpc fail: ' + e);
    }
  }

  function tryCompleteOnSaveNpc() {
    if (!CFG.forceOnSaveNpcComplete || onSaveNpcForced) return;
    if (!isSanePtr(lastOnSaveNpc)) return;
    var saved = 0;
    for (var k in followNavAt) {
      if (k.indexOf(':saved') > 0) saved++;
    }
    if (saved < 1) return;
    onSaveNpcForced = true;
    try {
      nfOnSaveForceComplete(lastOnSaveNpc);
      alog('OnSaveNpc.ForceCompleteCondition savedN=' + saved);
    } catch (e0) {
      alog('OnSaveNpc.ForceComplete fail: ' + e0);
    }
    setTimeout(function () {
      try {
        var val = nfGetValidator();
        if (isSanePtr(val) && !nfValidatorIsDone(val)) {
          nfValidatorDebugForce(val);
          alog('ConditionValidator.Debug_ForceComplete fallback');
        }
      } catch (e1) {
        alog('Debug_ForceComplete fail: ' + e1);
      }
      try {
        nfForceQuest();
        alog('ForceCompleteCurrentQuest after OnSaveNpc');
      } catch (e2) {
        alog('ForceCompleteCurrentQuest fail: ' + e2);
      }
    }, 1500);
  }

  function readBbObject(bbParam) {
    if (!isSanePtr(bbParam)) return null;
    var offs = [0x38, 0x40, 0x30, 0x28, 0x20];
    for (var i = 0; i < offs.length; i++) {
      try {
        var p = bbParam.add(offs[i]).readPointer();
        if (isSanePtr(p)) return p;
      } catch (e) {}
    }
    return null;
  }

  function readVec3(buf) {
    return {
      x: buf.readFloat(),
      y: buf.add(4).readFloat(),
      z: buf.add(8).readFloat()
    };
  }

  function posOfTransform(tr) {
    if (!isSanePtr(tr)) return null;
    var buf = Memory.alloc(16);
    try {
      nfGetPos(tr, buf);
    } catch (e) {
      return null;
    }
    var p = readVec3(buf);
    if (!(Math.abs(p.x) + Math.abs(p.z) > 0.5)) return null;
    return p;
  }

  function ensureBikeFromWorld() {
    if (isSanePtr(lastBikeQueue)) return lastBikeQueue;
    try {
      var w = nfWorldActual();
      if (!isSanePtr(w)) return null;
      // field _bikeQueue @0x70 (getter may return null before Awake wiring)
      var q = null;
      try {
        q = w.add(0x70).readPointer();
      } catch (eField) {}
      if (!isSanePtr(q)) {
        try {
          q = nfWorldBike(w);
        } catch (eGet) {}
      }
      if (isSanePtr(q)) {
        rememberBike(q);
        alog('BikeQueue from World field/getter');
      }
      return isSanePtr(lastBikeQueue) ? lastBikeQueue : null;
    } catch (e) {
      return null;
    }
  }

  function activateBikeQueue() {
    // Only resolve instance. Do NOT call SetActivate — it enables ride panel /
    // cutscene camera (low weird FOV). Game BT activates when ready.
    return ensureBikeFromWorld();
  }

  function rememberBikeEnter(cs) {
    if (isSanePtr(cs)) lastBikeEnterCutscene = cs;
  }

  function rememberPlayerCam(p) {
    if (isSanePtr(p)) lastPlayerCameraPoint = p;
  }

  function hardRestorePlayerCamera() {
    var bits = 0;
    try {
      nfStopTutorCam(1, 1);
      bits |= 1;
    } catch (e0) {}
    try {
      var handler = nfCamHandlerGet();
      if (isSanePtr(handler)) {
        var follower = handler.add(0x20).readPointer();
        if (isSanePtr(follower)) {
          follower.add(0x79).writeU8(0); // _disableSetTarget
          nfFollowerDisable(follower, 0);
          nfFollowerForce(follower, 0, 0, 0);
          bits |= 2;
        }
      }
    } catch (e1) {}
    if (isSanePtr(lastPlayerCameraPoint)) {
      try {
        // updateType=0 Instant, time~0 — restores normal player follow height/FOV
        nfPlayerCamApply(lastPlayerCameraPoint, 0, 0.01);
        nfPlayerCamSetTarget(lastPlayerCameraPoint);
        bits |= 4;
        // force follower again after retarget
        try {
          var h2 = nfCamHandlerGet();
          if (isSanePtr(h2)) {
            var f2 = h2.add(0x20).readPointer();
            if (isSanePtr(f2)) nfFollowerForce(f2, 0, 0, 0);
          }
        } catch (e3) {}
      } catch (e2) {
        alog('PlayerCameraPoint apply fail: ' + e2);
      }
    }
    return bits;
  }

  function softActivateBikeForEscort() {
    if (!CFG.softActivateBikeForEscort) return false;
    var bike = ensureBikeFromWorld();
    if (!isSanePtr(bike)) return false;
    try {
      // keep flag high every call (cheap); call SetActivate at most every 5s
      nfBikeDisablePanel(bike, 1);
      bike.add(0x67).writeU8(1);
      var now = Date.now();
      if (
        !CFG.disableBikeActivateCalls &&
        (!followNavAt['softActAt'] || now - followNavAt['softActAt'] > 5000)
      ) {
        followNavAt['softActAt'] = now;
        nfBikeSetActivate(bike, 1);
        alog('softActivate BikeQueue.SetActivate(true)+panel off');
      }
      try {
        var panel = bike.add(0x58).readPointer();
        if (isSanePtr(panel)) {
          var go = nfCompGameObject(panel);
          if (isSanePtr(go)) nfGoSetActive(go, 0);
        }
      } catch (e1) {}
      return true;
    } catch (e0) {
      return false;
    }
  }

  function recoverRideCamera() {
    if (!CFG.recoverCamera) return false;
    var ok = false;
    var bike = ensureBikeFromWorld();
    if (isSanePtr(bike)) {
      // ONLY hide ride panel / cutscene — do NOT SetActivate(false)
      // (that kills villager IsBikeQueueActivate and blocks quest)
      try {
        nfBikeDisablePanel(bike, 1);
        ok = true;
      } catch (e0) {}
      try {
        var panel = bike.add(0x58).readPointer();
        if (isSanePtr(panel)) {
          var go = nfCompGameObject(panel);
          if (isSanePtr(go)) {
            nfGoSetActive(go, 0);
            ok = true;
          }
        }
      } catch (e1) {}
    }
    if (isSanePtr(lastBikeEnterCutscene)) {
      try {
        lastBikeEnterCutscene.add(0xa9).writeU8(0);
        nfBikeEnterOnStop(lastBikeEnterCutscene);
        nfSetCamToPlayer(lastBikeEnterCutscene);
        ok = true;
      } catch (e2) {
        alog('recover cutscene fail: ' + e2);
      }
    }
    try {
      refreshPlayerTransform();
      if (isSanePtr(lastPlayerTr)) nfSetParent(lastPlayerTr, ptr(0), 1);
    } catch (e3) {}
    var camBits = hardRestorePlayerCamera();
    if (camBits) ok = true;
    alog(
      'recoverCam(noDeact) bike=' +
        (isSanePtr(bike) ? 1 : 0) +
        ' cut=' +
        (isSanePtr(lastBikeEnterCutscene) ? 1 : 0) +
        ' pcam=' +
        (isSanePtr(lastPlayerCameraPoint) ? 1 : 0) +
        ' bits=0x' +
        camBits.toString(16)
    );
    if (camBits & 4) cameraRecoverDone = true;
    return ok;
  }

  function recoverCameraAndStackedVillagers() {
    if (!CFG.recoverStuckOnBike && !CFG.recoverCamera) return;
    recoverRideCamera();
    if (!CFG.recoverStuckOnBike) {
      // cameraRecoverDone set only when ApplyCamera ran (bit4)
      return;
    }
    var bike = ensureBikeFromWorld();
    refreshPlayerTransform();
    var base = null;
    if (isSanePtr(lastPlayerTr)) base = posOfTransform(lastPlayerTr);
    if (!base && isSanePtr(bike)) {
      try {
        var selfTr = nfCompTransform(bike);
        base = posOfTransform(selfTr);
      } catch (e2) {}
    }
    var n = 0;
    for (var i = 0; i < trackedVillagers.length; i++) {
      var v = trackedVillagers[i];
      if (!isSanePtr(v)) continue;
      try {
        var villTr = nfCompTransform(v);
        if (isSanePtr(villTr)) nfSetParent(villTr, ptr(0), 1);
        var agent = v.add(0x98).readPointer();
        if (!isSanePtr(agent)) continue;
        nfBehEnabled(agent, 1);
        nfSetStopped(agent, 0);
        if (base) {
          var ox = ((i % 3) - 1) * 1.2;
          var oz = (Math.floor(i / 3) - 1) * 1.2;
          var posBuf = Memory.alloc(16);
          posBuf.writeFloat(base.x + ox);
          posBuf.add(4).writeFloat(base.y);
          posBuf.add(8).writeFloat(base.z + oz);
          nfSetDest(agent, posBuf);
        }
        n++;
      } catch (e3) {}
    }
    alog(
      'recover: cam=' +
        (camOk ? 1 : 0) +
        ' cutscene=' +
        (isSanePtr(lastBikeEnterCutscene) ? 1 : 0) +
        ' villagers=' +
        n
    );
    // cameraRecoverDone only set inside recoverRideCamera when ApplyCamera ran
  }

  function resolveBikeTransforms() {
    if (!isSanePtr(lastBikeQueue)) return [];
    var out = [];
    function push(name, tr) {
      if (!isSanePtr(tr)) return;
      var p = posOfTransform(tr);
      if (!p) return;
      out.push({ name: name, tr: tr, p: p });
    }
    try {
      push('QueuePoint', lastBikeQueue.add(0x38).readPointer() || nfGetQueuePoint(lastBikeQueue));
    } catch (e0) {}
    try {
      push('LookPoint', lastBikeQueue.add(0x40).readPointer() || nfGetLookPoint(lastBikeQueue));
    } catch (e1) {}
    try {
      push('Parent', lastBikeQueue.add(0x30).readPointer() || nfGetParent(lastBikeQueue));
    } catch (e2) {}
    try {
      push('Self', nfCompTransform(lastBikeQueue));
    } catch (e3) {}
    return out;
  }

  function logBikePointsOnce() {
    if (bikePointsLogged) return;
    var pts = resolveBikeTransforms();
    if (!pts.length) return;
    bikePointsLogged = true;
    for (var i = 0; i < pts.length; i++) {
      var t = pts[i];
      alog(
        'bike.' +
          t.name +
          '=(' +
          t.p.x.toFixed(1) +
          ',' +
          t.p.y.toFixed(1) +
          ',' +
          t.p.z.toFixed(1) +
          ')'
      );
    }
  }

  // Native BT walk target is QueuePoint (staging into bike queue).
  function getWalkTargetTransform() {
    var prefer = CFG.bikePrefer || 'queue';
    var pts = resolveBikeTransforms();
    var order =
      prefer === 'look'
        ? ['LookPoint', 'QueuePoint', 'Self', 'Parent']
        : prefer === 'parent'
          ? ['Parent', 'QueuePoint', 'Self', 'LookPoint']
          : prefer === 'self'
            ? ['Self', 'QueuePoint', 'LookPoint', 'Parent']
            : ['QueuePoint', 'LookPoint', 'Self', 'Parent'];
    for (var i = 0; i < order.length; i++) {
      for (var j = 0; j < pts.length; j++) {
        if (pts[j].name === order[i]) return pts[j];
      }
    }
    return pts.length ? pts[0] : null;
  }

  function collectWorldVillagers() {
    try {
      var w = nfWorldActual();
      if (!isSanePtr(w)) return;
      var arr = nfWorldVillagers(w);
      if (!isSanePtr(arr)) return;
      var len = arr.add(0x18).readS32();
      if (len < 0 || len > 64) return;
      for (var i = 0; i < len; i++) {
        var v = arr.add(0x20 + i * Process.pointerSize).readPointer();
        if (isSanePtr(v)) trackVillager(v);
      }
      if (shouldLog('worldVill')) alog('World.villagers n=' + len);
    } catch (e) {}
  }

  function scheduleForceAfterEndPath() {
    if (!CFG.forceCompleteAfterEndPath || endPathForceScheduled) return;
    endPathForceScheduled = true;
    setTimeout(function () {
      if (questForceDone) return;
      try {
        var v = nfGetValidator();
        if (isSanePtr(v)) {
          var done = !!nfValidatorIsDone(v);
          var prog = nfValidatorProgress(v);
          alog(
            'post-EndPath validator done=' +
              (done ? 1 : 0) +
              ' progress=' +
              prog.toFixed(2)
          );
          if (done) return;
        }
      } catch (e0) {}
      if (questForceDone) return;
      questForceDone = true;
      try {
        nfForceQuest();
        alog('ForceCompleteCurrentQuest after EndPathToBike (validator stuck)');
      } catch (e1) {
        alog('ForceComplete after EndPath fail: ' + e1);
      }
    }, CFG.forceCompleteEndPathWaitMs || 15000);
  }

  // WALK to QueuePoint, then HANDS-OFF; near bike -> OnSaveNpc.ForceSaveNpc
  function walkVillagerToBike(thiz) {
    if (!CFG.walkToBike && !CFG.sendVillagersToBike) return false;
    if (!isEscortCandidate(thiz)) return false;
    if (CFG.escortOneAtATime) {
      var active = pickActiveEscort();
      if (!active || active.toString() !== thiz.toString()) return false;
    }
    ensureFollowPlayer(thiz);
    var key = thiz.toString();
    if (CFG.handsOffNearQueue && followNavAt[key + ':handsOff']) {
      softActivateBikeForEscort();
      tryForceSaveNpcNear(thiz);
      tryCompleteOnSaveNpc();
      maybeSoftCompleteIfStuck(key);
      return true;
    }

    ensureBikeFromWorld();
    softActivateBikeForEscort();
    var tgt = getWalkTargetTransform();
    if (!tgt) {
      if (shouldLog('bikeWait')) alog('waiting BikeQueue.QueuePoint for walk');
      return false;
    }

    var agent = thiz.add(0x98).readPointer();
    if (!isSanePtr(agent)) return false;

    if (!followNavAt[key + ':prep']) {
      followNavAt[key + ':prep'] = 1;
      try {
        nfSetSick(thiz, 0);
        thiz.add(0x5a).writeU8(0);
      } catch (e0) {}
    }

    try {
      nfBehEnabled(agent, 1);
      nfSetStopped(agent, 0);
    } catch (e2) {}

    var remain = 999;
    try {
      remain = nfRemainDist(agent);
    } catch (e3) {}
    if (remain >= 0 && remain < (CFG.bikeArriveDist || 3.0)) {
      if (!followNavAt[key + ':handsOff']) {
        followNavAt[key + ':handsOff'] = 1;
        followNavAt[key + ':nearAt'] = Date.now();
        try {
          var tree = thiz.add(0x68).readPointer();
          if (isSanePtr(tree)) nfStartBt(tree);
        } catch (eBt) {}
        alog('HANDS-OFF near ' + tgt.name + ' remain=' + remain.toFixed(2));
      }
      tryForceSaveNpcNear(thiz);
      tryCompleteOnSaveNpc();
      maybeSoftCompleteIfStuck(key);
      return true;
    }

    var posBuf = Memory.alloc(16);
    posBuf.writeFloat(tgt.p.x);
    posBuf.add(4).writeFloat(tgt.p.y);
    posBuf.add(8).writeFloat(tgt.p.z);
    var ok = false;
    try {
      ok = !!nfSetDest(agent, posBuf);
    } catch (e4) {
      ok = false;
    }
    if (shouldLog('bikeNav')) {
      alog(
        'WALK SetDest -> ' +
          tgt.name +
          ' (' +
          tgt.p.x.toFixed(1) +
          ',' +
          tgt.p.z.toFixed(1) +
          ') ok=' +
          (ok ? 1 : 0)
      );
    }
    return ok;
  }

  function maybeSoftCompleteIfStuck(key) {
    if (!CFG.softCompleteBike) return;
    if (endPathToBikeSeen) return;
    var nearAt = followNavAt[key + ':nearAt'] || 0;
    if (!nearAt) return;
    if (Date.now() - nearAt < (CFG.stuckNearMs || 25000)) return;
    if (followNavAt['softBikeOnce']) return;
    followNavAt['softBikeOnce'] = 1;
    var bike = ensureBikeFromWorld();
    if (!isSanePtr(bike)) return;
    try {
      softActivateBikeForEscort();
      // last-resort: call game setters (prefer real SetNpc path first)
      nfBikeIncrease(bike);
      nfBikeEndPathToBike(bike, 1);
      nfBikeEndPath(bike, 1);
      bike.add(0x65).writeU8(1);
      alog('softCompleteBike Increase+EndPath after stuck near (BT board missing)');
      scheduleForceAfterEndPath();
    } catch (e) {
      alog('softCompleteBike fail: ' + e);
    }
  }

  // capture PlayerCameraPoint (fires every frame) for hard camera restore
  Interceptor.attach(il2cpp.base.add(RVA.PlayerCameraPoint_Awake), {
    onEnter: function (args) {
      rememberPlayerCam(args[0]);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.PlayerCameraPoint_Update), {
    onEnter: function (args) {
      rememberPlayerCam(args[0]);
    }
  });

  // capture ride cutscene so we can OnStop + restore camera
  Interceptor.attach(il2cpp.base.add(RVA.PlayerBikeEnter_Awake), {
    onEnter: function (args) {
      rememberBikeEnter(args[0]);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.PlayerBikeEnter_Update), {
    onEnter: function (args) {
      rememberBikeEnter(args[0]);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.PlayerBikeEnter_OnPlay), {
    onEnter: function (args) {
      rememberBikeEnter(args[0]);
      alog('PlayerBikeEnter.OnPlay (ride camera)');
      if (CFG.blockRideCutscene || CFG.recoverCamera) {
        var self = args[0];
        setTimeout(function () {
          try {
            if (!isSanePtr(self)) return;
            self.add(0xa9).writeU8(0);
            nfBikeEnterOnStop(self);
            nfSetCamToPlayer(self);
            hardRestorePlayerCamera();
            alog('auto-stop PlayerBikeEnter + hardRestoreCamera');
          } catch (e) {}
        }, 30);
      }
    }
  });

  // capture BikeQueue instances (observe only — do not force-activate)
  Interceptor.attach(il2cpp.base.add(RVA.BikeQueue_SetActivate), {
    onEnter: function (args) {
      rememberBike(args[0]);
      if (shouldLog('bikeAct')) {
        alog('BikeQueue.SetActivate=' + args[1].toInt32() + ' (game)');
      }
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.BikeQueue_get_QueuePoint), {
    onEnter: function (args) {
      rememberBike(args[0]);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.BikeQueue_get_LookPoint), {
    onEnter: function (args) {
      rememberBike(args[0]);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.BikeQueue_get_Parent), {
    onEnter: function (args) {
      rememberBike(args[0]);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.BikeQueue_ctor), {
    onEnter: function (args) {
      rememberBike(args[0]);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.FindBikeQueue_OnExecute), {
    onEnter: function (args) {
      this.self = args[0];
    },
    onLeave: function () {
      try {
        var bb = this.self.add(0x68).readPointer();
        var q = readBbObject(bb);
        if (isSanePtr(q)) {
          rememberBike(q);
          if (shouldLog('findBike')) alog('FindBikeQueue captured');
        }
      } catch (e) {}
    }
  });

  Interceptor.attach(il2cpp.base.add(RVA.IsBikeQueueActivate_OnCheck), {
    onLeave: function (retval) {
      if (shouldLog('bikeActCheck')) {
        alog('IsBikeQueueActivate=' + retval.toInt32());
      }
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.FindBikeQueuePoint_OnExecute), {
    onEnter: function () {
      if (shouldLog('findQP')) alog('FindBikeQueuePoint.OnExecute');
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.OnSaveNpc_InitCondition), {
    onEnter: function (args) {
      if (isSanePtr(args[0])) {
        lastOnSaveNpc = args[0];
        alog('OnSaveNpc.InitCondition captured');
      }
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.OnSaveNpc_ForceSaveNpc), {
    onEnter: function (args) {
      if (isSanePtr(args[0])) lastOnSaveNpc = args[0];
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.OnSaveNpc_TrackProgress), {
    onEnter: function (args) {
      if (isSanePtr(args[0])) lastOnSaveNpc = args[0];
      alog('OnSaveNpc.TrackProgress');
    }
  });

  // Kill branch2 "Upgrade pickaxe" stealing the top quest panel
  if (CFG.killPickaxeQuestSteal) {
    var nfBranchGetActive = new NativeFunction(
      il2cpp.base.add(RVA.QuestBranch_GetActiveQuest),
      'pointer',
      ['pointer']
    );
    var nfBranchGetName = new NativeFunction(
      il2cpp.base.add(RVA.QuestBranch_GetActivatedQuestName),
      'pointer',
      ['pointer']
    );
    Interceptor.attach(il2cpp.base.add(RVA.QuestBranch_CreateActivatedQuest), {
      onEnter: function (args) {
        this.self = args[0];
        this.qname = readIl2CppString(args[1]);
        alog('CreateActivatedQuest: ' + this.qname);
      },
      onLeave: function () {
        if (!isPickaxeQuestName(this.qname)) return;
        if (pickaxeStealKilled) return;
        var self = this.self;
        setTimeout(function () {
          try {
            var val = nfBranchGetActive(self);
            if (isSanePtr(val) && !nfValidatorIsDone(val)) {
              nfValidatorDebugForce(val);
              pickaxeStealKilled = true;
              alog('killed pickaxe quest steal via Debug_ForceComplete');
            }
          } catch (e) {
            alog('kill pickaxe fail: ' + e);
          }
        }, 200);
      }
    });
    Interceptor.attach(il2cpp.base.add(RVA.CheckEquipmentLevel_InitCondition), {
      onEnter: function (args) {
        alog('CheckEquipmentLevel.InitCondition (likely pickaxe UI)');
        var cond = args[0];
        if (pickaxeStealKilled || !isSanePtr(cond)) return;
        setTimeout(function () {
          try {
            // complete whatever validator currently owns the panel if still pickaxe
            var val = nfGetValidator();
            if (isSanePtr(val) && !nfValidatorIsDone(val)) {
              // only if OnSaveNpc not captured yet — avoid nuking escort
              if (isSanePtr(lastOnSaveNpc)) {
                alog('skip kill pickaxe: OnSaveNpc already active');
                return;
              }
              nfValidatorDebugForce(val);
              pickaxeStealKilled = true;
              alog('ForceComplete current validator (pickaxe steal)');
            }
          } catch (e) {}
        }, 500);
      }
    });
    setInterval(function () {
      if (!warmedUp || pickaxeStealKilled) return;
      try {
        // poll any branch controllers we saw via CreateActivatedQuest is enough;
        // also log GetCurrentValidator progress for diagnosis
        var val = nfGetValidator();
        if (!isSanePtr(val)) return;
        var prog = nfValidatorProgress(val);
        if (shouldLog('valPoll')) {
          alog(
            'validator done=' +
              (nfValidatorIsDone(val) ? 1 : 0) +
              ' prog=' +
              prog.toFixed(2) +
              ' onSave=' +
              (isSanePtr(lastOnSaveNpc) ? 1 : 0)
          );
        }
      } catch (e) {}
    }, 8000);
    alog('pickaxe quest-steal killer ON');
  }

  try {
    Interceptor.attach(il2cpp.base.add(RVA.UIQuestInfo_SetQuestInfo), {
      onEnter: function (args) {
        // args[1] is QuestSerializedInfo by value/ref — skip deep parse; log call
        alog('UIQuestInfo.SetQuestInfo');
      }
    });
  } catch (eUi) {}

  // observe boarding path (do not replace)
  Interceptor.attach(il2cpp.base.add(RVA.SetNpcToBike_OnExecute), {
    onEnter: function (args) {
      try {
        var bb = args[0].add(0x68).readPointer();
        var q = readBbObject(bb);
        if (isSanePtr(q)) rememberBike(q);
      } catch (e) {}
      endPathToBikeSeen = true; // boarding started — cancel softComplete urgency
      alog('SetNpcToBike.OnExecute (boarding anim path)');
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.BikeQueue_IncreaseVillagersCount), {
    onEnter: function (args) {
      rememberBike(args[0]);
      alog('BikeQueue.IncreaseVillagersCount');
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.BikeQueue_SetIsVillagerEndPathToBike), {
    onEnter: function (args) {
      rememberBike(args[0]);
      var en = args[1].toInt32();
      if (en) {
        endPathToBikeSeen = true;
        alog('BikeQueue.SetIsVillagerEndPathToBike=1');
        scheduleForceAfterEndPath();
      }
    }
  });

  if (CFG.blockFollowChain) {
    Interceptor.replace(
      il2cpp.base.add(RVA.FollowChain_OnUpdate),
      new NativeCallback(
        function (thiz) {
          return;
        },
        'void',
        ['pointer']
      )
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.FollowChain_OnExecute),
      new NativeCallback(
        function (thiz) {
          return;
        },
        'void',
        ['pointer']
      )
    );
    alog('FollowTargetInChain disabled');
  } else {
    alog('FollowTargetInChain left intact (BT can reach SetNpcToBike)');
  }

  if (CFG.followIgnoreSick) {
    var origIsSick = new NativeFunction(
      il2cpp.base.add(RVA.VillagerState_get_IsSick),
      'bool',
      ['pointer']
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.VillagerState_get_IsSick),
      new NativeCallback(
        function (thiz) {
          try {
            trackVillager(thiz);
            if (isSanePtr(thiz) && isEscortCandidate(thiz)) {
              return 0;
            }
          } catch (e) {}
          return origIsSick(thiz);
        },
        'bool',
        ['pointer']
      )
    );
    alog('FollowPlayer get_IsSick -> false');
  }

  Interceptor.attach(il2cpp.base.add(RVA.VillagerState_SetActivate), {
    onEnter: function (args) {
      trackVillager(args[0]);
    }
  });
  Interceptor.attach(il2cpp.base.add(RVA.VillagerState_SetSickState), {
    onEnter: function (args) {
      trackVillager(args[0]);
    }
  });

  if (CFG.walkToBike || CFG.sendVillagersToBike) {
    Interceptor.attach(il2cpp.base.add(RVA.VillagerState_SetActionType), {
      onEnter: function (args) {
        trackVillager(args[0]);
        try {
          var a = args[1].toInt32();
          if (isEscortAction(a) && shouldLog('setAct')) {
            alog('SetActionType=' + a);
          }
        } catch (e) {}
      }
    });
    Interceptor.attach(il2cpp.base.add(RVA.VillagerState_Update), {
      onEnter: function (args) {
        var thiz = args[0];
        if (!isSanePtr(thiz)) return;
        trackVillager(thiz);
        if (!warmedUp) return;
        try {
          if (!isEscortCandidate(thiz)) return;
          var key = thiz.toString();
          var now = Date.now();
          if (followNavAt[key] && now - followNavAt[key] < CFG.bikeNavIntervalMs) {
            return;
          }
          followNavAt[key] = now;
          walkVillagerToBike(thiz);
        } catch (e) {}
      }
    });
    setInterval(function () {
      if (!warmedUp || !(CFG.walkToBike || CFG.sendVillagersToBike)) return;
      collectWorldVillagers();
      ensureBikeFromWorld();
      for (var i = 0; i < trackedVillagers.length; i++) {
        var v = trackedVillagers[i];
        if (!isSanePtr(v)) continue;
        try {
          if (!isEscortCandidate(v)) continue;
          var key = v.toString();
          var now = Date.now();
          if (followNavAt[key] && now - followNavAt[key] < CFG.bikeNavIntervalMs) {
            continue;
          }
          followNavAt[key] = now;
          walkVillagerToBike(v);
        } catch (e) {}
      }
    }, CFG.bikeNavIntervalMs);
    // camera recover ASAP (do not wait warm-up); retry until PlayerCameraPoint seen
    var recoverTries = 0;
    var recoverTimer = setInterval(function () {
      recoverTries++;
      collectWorldVillagers();
      ensureBikeFromWorld();
      recoverCameraAndStackedVillagers();
      if (cameraRecoverDone || recoverTries >= 20) {
        clearInterval(recoverTimer);
        alog(
          'recover loop end tries=' +
            recoverTries +
            ' done=' +
            (cameraRecoverDone ? 1 : 0) +
            ' pcam=' +
            (isSanePtr(lastPlayerCameraPoint) ? 1 : 0)
        );
      }
    }, 1500);
    alog('Escort v3.11: one-at-a-time + OnSaveNpc (skip-rescued order)');
  }

  if (CFG.forceCompleteCurrentQuest) {
    setTimeout(function () {
      if (questForceDone) return;
      questForceDone = true;
      try {
        nfForceQuest();
        alog('ForceCompleteCurrentQuest (blind timer)');
      } catch (e) {
        alog('ForceCompleteCurrentQuest fail: ' + e);
      }
    }, 15000);
    alog('ForceCompleteCurrentQuest scheduled (blind timer)');
  } else {
    alog(
      'ForceComplete after EndPath=' +
        (CFG.forceCompleteAfterEndPath ? 1 : 0) +
        ' softCompleteBike=' +
        (CFG.softCompleteBike ? 1 : 0)
    );
  }

  var origCalc = new NativeFunction(il2cpp.base.add(RVA.CalcMoveParams), 'float', ['pointer']);
  Interceptor.replace(
    il2cpp.base.add(RVA.CalcMoveParams),
    new NativeCallback(
      function (thiz) {
        try {
          origCalc(thiz);
        } catch (e) {}
        return 1.0;
      },
      'float',
      ['pointer']
    )
  );

  var origSyncExt = new NativeFunction(
    il2cpp.base.add(RVA.SyncExtractionSpeed_UpdateValue),
    'void',
    ['pointer', 'float']
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.SyncExtractionSpeed_UpdateValue),
    new NativeCallback(
      function (thiz, v) {
        var nv = Math.max(v, 1) * CFG.pickaxeAnimMul;
        return origSyncExt(thiz, nv);
      },
      'void',
      ['pointer', 'float']
    )
  );

  if (CFG.ignorePickLevel) {
    Interceptor.replace(
      il2cpp.base.add(RVA.IsResourceLockedByItem),
      new NativeCallback(
        function (thiz, res) {
          return 0;
        },
        'bool',
        ['pointer', 'pointer']
      )
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.IsLevelPassed),
      new NativeCallback(
        function (thiz, resource, level) {
          return 1;
        },
        'bool',
        ['pointer', 'pointer', 'int']
      )
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.IsResourceReqPassed_Eq),
      new NativeCallback(
        function (thiz, equipment, resource) {
          return 1;
        },
        'bool',
        ['pointer', 'pointer', 'pointer']
      )
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.IsResourceReqPassed_Inv),
      new NativeCallback(
        function (thiz, inventory, resource) {
          return 1;
        },
        'bool',
        ['pointer', 'pointer', 'pointer']
      )
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.IsReqPassed_Slot),
      new NativeCallback(
        function (thiz, tool) {
          return 1;
        },
        'bool',
        ['pointer', 'pointer']
      )
    );
    alog('pick level gates bypassed (no ShowMeHow / ReqLvl)');
  }

  if (CFG.ignoreVacuumLevel) {
    Interceptor.replace(
      il2cpp.base.add(RVA.HasRequiredTool_Player),
      new NativeCallback(
        function (thiz, requiredLevel, sand) {
          return 1;
        },
        'bool',
        ['pointer', 'int', 'int']
      )
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.HasRequiredTool_Worker),
      new NativeCallback(
        function (thiz, requiredLevel) {
          return 1;
        },
        'bool',
        ['pointer', 'int']
      )
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.SandPlaneValidationCallback_Player),
      new NativeCallback(
        function (thiz, sandPlane) {
          return 1;
        },
        'bool',
        ['pointer', 'pointer']
      )
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.SandPlaneValidationCallback_Worker),
      new NativeCallback(
        function (thiz, sandPlane) {
          return 1;
        },
        'bool',
        ['pointer', 'pointer']
      )
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.SandPlaneValidation_Brush),
      new NativeCallback(
        function (thiz, sandPlane) {
          return 1;
        },
        'bool',
        ['pointer', 'pointer']
      )
    );
    Interceptor.replace(
      il2cpp.base.add(RVA.HasRequiredToolInHand),
      new NativeCallback(
        function (thiz) {
          return 1;
        },
        'bool',
        ['pointer']
      )
    );
    alog('vacuum level gates bypassed');
  }

  var origRange = new NativeFunction(il2cpp.base.add(RVA.SetInteractRange), 'void', [
    'pointer',
    'float'
  ]);
  Interceptor.replace(
    il2cpp.base.add(RVA.SetInteractRange),
    new NativeCallback(
      function (thiz, value) {
        return origRange(thiz, CFG.pickaxeRange);
      },
      'void',
      ['pointer', 'float']
    )
  );

  Interceptor.attach(il2cpp.base.add(RVA.Extraction_SetAttributes), {
    onLeave: function () {
      if (!warmedUp) return;
      for (var k in tracked) {
        if (!Object.prototype.hasOwnProperty.call(tracked, k)) continue;
        if (tracked[k].attr === ATTR.ExtractionDamage) {
          forceBvValue(ptr(k), CFG.pickaxeDamage, 'PickDmg');
        }
      }
    }
  });

  Interceptor.attach(il2cpp.base.add(RVA.BrushSolid_DoWork), {
    onEnter: function (args) {
      if (!warmedUp) return;
      try {
        var brush = args[0];
        if (!isSanePtr(brush)) return;
        var key = brush.toString();
        if (!brushBase[key]) {
          brushBase[key] = {
            solid: brush.add(0x20).readFloat(),
            radius: brush.add(0x28).readFloat(),
            smooth: brush.add(0x2c).readFloat()
          };
        }
        var b = brushBase[key];
        if (!(b.radius > 0) || !(b.solid > 0)) return;
        var ns = b.solid * CFG.vacuumSolidMul;
        var nr = b.radius * RAD_MUL;
        var nSmooth = Math.max(0.01, b.smooth / CFG.vacuumSolidMul);
        brush.add(0x20).writeFloat(ns);
        brush.add(0x28).writeFloat(nr);
        brush.add(0x2c).writeFloat(nSmooth);
        try {
          brush.add(0x60).writeFloat(ns);
        } catch (e2) {}
      } catch (e) {}
    }
  });
  alog('SandBrush gated by warm-up');

  // ---- flamethrower level + damage (no get_ReqLvl) ----
  Interceptor.replace(
    il2cpp.base.add(RVA.VineGroup_get_Level),
    new NativeCallback(
      function (thiz) {
        return 0;
      },
      'int',
      ['pointer']
    )
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.VineGroupCollider_get_Level),
    new NativeCallback(
      function (thiz) {
        return 0;
      },
      'int',
      ['pointer']
    )
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.VineGroupCollider_get_IsLock),
    new NativeCallback(
      function (thiz) {
        return 0;
      },
      'bool',
      ['pointer']
    )
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.Flamethrower_GetLevel),
    new NativeCallback(
      function (thiz) {
        return CFG.flameLevel;
      },
      'int',
      ['pointer']
    )
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.Flamethrower_ShowPopup),
    new NativeCallback(
      function (thiz, msg, localization) {
        return;
      },
      'void',
      ['pointer', 'pointer', 'pointer']
    )
  );
  var origSetFlameDmg = new NativeFunction(
    il2cpp.base.add(RVA.VineDamage_SetDamage),
    'void',
    ['pointer', 'int', 'float']
  );
  Interceptor.replace(
    il2cpp.base.add(RVA.VineDamage_SetDamage),
    new NativeCallback(
      function (thiz, damage, time) {
        var d = CFG.flameTickDamage | 0;
        if (d > 32767) d = 32767;
        if (d < 1) d = 1;
        return origSetFlameDmg(thiz, d, CFG.flameTickTime);
      },
      'void',
      ['pointer', 'int', 'float']
    )
  );
  alog('flamethrower level bypass + damage boost');

  // ---- hold interactions: finish via game APIs after start path ----
  var nfPutOutStop = new NativeFunction(il2cpp.base.add(RVA.PutOut_Stop), 'void', [
    'pointer',
    'bool'
  ]);
  var nfLatticeComplete = new NativeFunction(
    il2cpp.base.add(RVA.Lattice_CompleteBreak),
    'void',
    ['pointer']
  );
  var nfIceComplete = new NativeFunction(il2cpp.base.add(RVA.Ice_CompleteMelt), 'void', [
    'pointer'
  ]);

  if (CFG.instantPutOut) {
    Interceptor.attach(il2cpp.base.add(RVA.PutOut_AddCycle), {
      onEnter: function (args) {
        this.self = args[0];
      },
      onLeave: function () {
        callHoldComplete(
          function (t) {
            nfPutOutStop(t, 1);
          },
          this.self,
          'PutOut'
        );
      }
    });
  }

  if (CFG.instantLattice) {
    Interceptor.attach(il2cpp.base.add(RVA.Lattice_PlayAnimation), {
      onEnter: function (args) {
        this.self = args[0];
      },
      onLeave: function () {
        callHoldComplete(nfLatticeComplete, this.self, 'Lattice');
      }
    });
  }

  if (CFG.instantIce) {
    Interceptor.attach(il2cpp.base.add(RVA.Ice_StartMelt), {
      onEnter: function (args) {
        this.self = args[0];
      },
      onLeave: function () {
        callHoldComplete(nfIceComplete, this.self, 'IceMelt');
      }
    });
  }
  alog('hold completes: putOut/lattice/ice (after warm-up, reentrancy-guarded)');

  if (!CFG.speedCraftTimers) {
    alog('craft timers disabled by CFG');
  } else {
    alog('craft timers scheduled after warm-up');
  }

  alog('wasteland boost v3.11 ready');
}

function main() {
  alog('========== wasteland boost v3.11 start ==========');
  waitModule('libil2cpp.so', install);
}

if (globalThis[FLAG]) {
  alog('already installed, skip');
} else {
  globalThis[FLAG] = true;
  setImmediate(main);
}
