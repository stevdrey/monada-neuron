package monada.neuron.routing.adaptation.codec;

import monada.neuron.routing.adaptation.RoutingStateCheckpoint;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The golden vectors of {@code docs/specs/routing-checkpoint-v1.md}, Section 7. Each is checked against the
 * independent {@link ReferenceCheckpointWriter} and then pinned as hex. The dormant cohort vector needs a real store,
 * so it lives in {@code CheckpointCodecRestoreTest}.
 */
class CheckpointGoldenVectorTest {

    static final String EMPTY_HEX =
            "00000014726f7574696e672d636865636b706f696e742f310000000a73636f70652d64656d6f0000000a666561747572"
            + "65732f310000000b666f7267652d676174657300000001310000000f61646170746976652d746573742f310000000870"
            + "6f6c6963792d780000000133000000000000000000000000000000000000000000000000000000000000000000000000"
            + "0000000000000000000000000000000000000000d5ad1384a8b0efcbd21fc11a2f61f6e7494c95d1e377ce6bf5b5991f"
            + "aca90ec7";

    static final String POPULATED_HEX =
            "00000014726f7574696e672d636865636b706f696e742f310000000a73636f70652d64656d6f0000000a666561747572"
            + "65732f310000000b666f7267652d676174657300000001310000000f61646170746976652d746573742f310000000870"
            + "6f6c6963792d7800000001330000000000000014000000020000000a73636f70652d64656d6f00000009696d706c656d"
            + "656e7400000007554e4b4e4f574e000000057375622d6100000000000000013fe800000000000080000000000000003f"
            + "f80000000000004000000000000000000000030000000a73636f70652d64656d6f00000009696d706c656d656e740000"
            + "000b6275636b65742df09f9880000000057375622d620000000000000002bfd00000000000003fe00000000000000000"
            + "0000000000003ff000000000000000000000000000000000000a0123456789abcdef0123456789abcdef0123456789ab"
            + "cdef0123456789abcdef00000002000000056578702d61000000000000000000000000000000050000000a73636f7065"
            + "2d64656d6f000000067461736b2d3100000006657865632d3100000009617474656d70742d310000000773746167652d"
            + "310000000000000003000000057375622d6100000000000000010000000f61646170746976652d746573742f31000000"
            + "0b666f7267652d6761746573000000013100000008706f6c6963792d7800000001330000000009636174616c6f672d37"
            + "0000000000000005000002000000114e4f5f5441524745545f42494e44494e4700000007554e4b4e4f574e0000000962"
            + "75636b6574732f310000000f61646170746976652d746573742f310000000a66656174757265732f31000000074e4555"
            + "5452414c0000000000000000000000086578702df09f9880000000000000000100000000000000090000000a73636f70"
            + "652d64656d6f000000067461736b2d3100000006657865632d3100000009617474656d70742d31000000077374616765"
            + "2d310000000000000003000000057375622d6100000000000000010000000f61646170746976652d746573742f310000"
            + "000b666f7267652d6761746573000000013100000008706f6c6963792d78000000013301000000000000000400000009"
            + "636174616c6f672d37000000000000000c0000020000001350524f56454e414e43455f4d49534d415443480000000755"
            + "4e4b4e4f574e000000096275636b6574732f310000000f61646170746976652d746573742f310000000a666561747572"
            + "65732f31000000074e45555452414c800000000000000038c7228e39054082b7f173b46461b0a110ab6e32e592630f8a"
            + "62bc33363464e5";

    private enum Vector {
        EMPTY(EMPTY_HEX, CheckpointFixtures::empty),
        POPULATED(POPULATED_HEX, CheckpointFixtures::populated);

        final String hex;
        final Supplier<RoutingStateCheckpoint> checkpoint;

        Vector(String hex, Supplier<RoutingStateCheckpoint> checkpoint) {
            this.hex = hex;
            this.checkpoint = checkpoint;
        }
    }

    @Test
    void theCodecMatchesTheReferenceWriterAndThePinnedBytes() {
        for (Vector vector : Vector.values()) {
            var checkpoint = vector.checkpoint.get();
            byte[] encoded = CheckpointCodec.encode(checkpoint);
            assertArrayEquals(ReferenceCheckpointWriter.write(checkpoint), encoded, vector.name());
            assertEquals(vector.hex, HexFormat.of().formatHex(encoded), vector.name());
        }
    }

    @Test
    void thePinnedBytesDecodeToTheCheckpoint() {
        for (Vector vector : Vector.values()) {
            var decoded = assertInstanceOf(CheckpointDecodeResult.Decoded.class,
                    CheckpointCodec.decode(HexFormat.of().parseHex(vector.hex)), vector.name());
            assertEquals(vector.checkpoint.get(), decoded.checkpoint(), vector.name());
        }
    }
}
