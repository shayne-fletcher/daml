package com.example

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike
import scala.concurrent.duration._

class DeviceSpec extends ScalaTestWithActorTestKit with WordSpecLike {
  import Device._

  "Device actor" must {

    "reply with empty reading if no temperature is known" in {
      val probe = createTestProbe[RespondTemperature]()
      val deviceActor = spawn(Device("group", "device"))

      deviceActor ! Device.ReadTemperature(requestId = 42, probe.ref)
      val response = probe.receiveMessage()
      response.requestId should ===(42)
      response.value should ===(None)
    }
  }

  "reply with latest temperature reading" in {
    val recordProbe = createTestProbe[TemperatureRecorded]()
    val readProbe = createTestProbe[RespondTemperature]()
    val deviceActor = spawn(Device("group", "device"))

    deviceActor ! Device.RecordTemperature(requestId = 1, 24.0, recordProbe.ref)
    recordProbe.expectMessage(Device.TemperatureRecorded(requestId = 1))

    deviceActor ! Device.ReadTemperature(requestId = 2, readProbe.ref)
    val response1 = readProbe.receiveMessage()
    response1.requestId should ===(2)
    response1.value should ===(Some(24.0))

    deviceActor ! Device.RecordTemperature(requestId = 3, 55.0, recordProbe.ref)
    recordProbe.expectMessage(Device.TemperatureRecorded(requestId = 3))

    deviceActor ! Device.ReadTemperature(requestId = 4, readProbe.ref)
    val response2 = readProbe.receiveMessage()
    response2.requestId should ===(4)
    response2.value should ===(Some(55.0))
  }
}

class DeviceGroupSpec extends ScalaTestWithActorTestKit with WordSpecLike {
  import Device._
  import DeviceGroup._
  import DeviceManager._

  "Device group" must {
    "be able to register a device actor" in {
      val probe = createTestProbe[DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor ! RequestTrackDevice("group", "device1", probe.ref)
      val registered1 = probe.receiveMessage()
      val deviceActor1 = registered1.device

      // another deviceId
      groupActor ! RequestTrackDevice("group", "device2", probe.ref)
      val registered2 = probe.receiveMessage()
      val deviceActor2 = registered2.device
      deviceActor1 should !==(deviceActor2)

      // Check that the device actors are working
      val recordProbe = createTestProbe[TemperatureRecorded]()
      deviceActor1 ! RecordTemperature(requestId = 0, 1.0, recordProbe.ref)
      recordProbe.expectMessage(TemperatureRecorded(requestId = 0))
      deviceActor2 ! Device.RecordTemperature(requestId = 1, 2.0, recordProbe.ref)
      recordProbe.expectMessage(Device.TemperatureRecorded(requestId = 1))
    }

    "ignore requests for wrong groupId" in {
      val probe = createTestProbe[DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor ! RequestTrackDevice("wrongGroup", "device1", probe.ref)
      probe.expectNoMessage(500.milliseconds)
    }

    "return same actor for same deviceId" in {
      val probe = createTestProbe[DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor ! RequestTrackDevice("group", "device1", probe.ref)
      val registered1 = probe.receiveMessage()

      // registering same again should be idempotent
      groupActor ! RequestTrackDevice("group", "device1", probe.ref)
      val registered2 = probe.receiveMessage()

      registered1.device should ===(registered2.device)
    }
  }

  "be able to list active devices" in {
    val registeredProbe = createTestProbe[DeviceRegistered]()
    val groupActor = spawn(DeviceGroup("group"))

    groupActor ! RequestTrackDevice("group", "device1", registeredProbe.ref)
    registeredProbe.receiveMessage()

    groupActor ! RequestTrackDevice("group", "device2", registeredProbe.ref)
    registeredProbe.receiveMessage()

    val deviceListProbe = createTestProbe[ReplyDeviceList]()
    groupActor ! RequestDeviceList(requestId = 0, groupId = "group", deviceListProbe.ref)
    deviceListProbe.expectMessage(ReplyDeviceList(requestId = 0, Set("device1", "device2")))
  }

  "be able to list active devices after one shuts down" in {
    val registeredProbe = createTestProbe[DeviceRegistered]()
    val groupActor = spawn(DeviceGroup("group"))

    groupActor ! RequestTrackDevice("group", "device1", registeredProbe.ref)
    val registered1 = registeredProbe.receiveMessage()
    val toShutDown = registered1.device

    groupActor ! RequestTrackDevice("group", "device2", registeredProbe.ref)
    registeredProbe.receiveMessage()

    val deviceListProbe = createTestProbe[ReplyDeviceList]()
    groupActor ! RequestDeviceList(requestId = 0, groupId = "group", deviceListProbe.ref)
    deviceListProbe.expectMessage(ReplyDeviceList(requestId = 0, Set("device1", "device2")))

    toShutDown ! Passivate
    registeredProbe.expectTerminated(toShutDown, registeredProbe.remainingOrDefault)

    // using awaitAssert to retry because it might take longer for the groupActor
    // to see the Terminated, that order is undefined
    registeredProbe.awaitAssert {
      groupActor ! RequestDeviceList(requestId = 1, groupId = "group", deviceListProbe.ref)
      deviceListProbe.expectMessage(ReplyDeviceList(requestId = 1, Set("device2")))
    }
  }
}

class DeviceGroupQuerySpec extends ScalaTestWithActorTestKit with WordSpecLike {
  import Device.Command
  import DeviceManager.{Temperature, RespondAllTemperatures}
  import DeviceGroupQuery.WrappedRespondTemperature

  "Device group query" must {
    "return temperature value for working devices" in {
      val requester = createTestProbe[RespondAllTemperatures]()

      val device1 = createTestProbe[Command]()
      val device2 = createTestProbe[Command]()

      val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

      val queryActor =
        spawn(DeviceGroupQuery(deviceIdToActor, requestId = 1, requester = requester.ref, timeout = 3.seconds))

      device1.expectMessageType[Device.ReadTemperature]
      device2.expectMessageType[Device.ReadTemperature]

      queryActor ! WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", Some(1.0)))
      queryActor ! WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device2", Some(2.0)))

      requester.expectMessage(
        RespondAllTemperatures(
          requestId = 1,
          temperatures = Map("device1" -> Temperature(1.0), "device2" -> Temperature(2.0))))
    }
  }
}
