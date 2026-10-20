package com.peaknav.utils;

import com.peaknav.shaded.osmpbf.Osmformat;
import org.junit.Test;
import static org.junit.Assert.*;

public class PbfRuntimeTest {
    @Test public void headerDecodesAlongsideTheHostPatchedRuntime() throws Exception {
        Class<?> legacyBytes = Class.forName("com.google.protobuf.ByteString");
        assertNotNull(legacyBytes.getMethod("copyFromUtf8", String.class).invoke(null, "host"));
        Osmformat.HeaderBlock header = Osmformat.HeaderBlock.newBuilder()
                .addRequiredFeatures("OsmSchema-V0.6").addRequiredFeatures("DenseNodes").build();
        Osmformat.HeaderBlock decoded = Osmformat.HeaderBlock.parseFrom(header.toByteArray());
        assertEquals("DenseNodes", decoded.getRequiredFeatures(1));
        assertTrue(decoded.getClass().getSuperclass().getName().startsWith("com.peaknav.shaded.protobuf."));
    }
    @Test public void unicodeNamesAndSignedCoordinatesSurvivePbfParsing() throws Exception {
        Osmformat.StringTable strings = Osmformat.StringTable.newBuilder()
                .addS(com.peaknav.shaded.protobuf.ByteString.copyFromUtf8(""))
                .addS(com.peaknav.shaded.protobuf.ByteString.copyFromUtf8("name"))
                .addS(com.peaknav.shaded.protobuf.ByteString.copyFromUtf8("\u5c0f\u4e94\u53f0"))
                .build();
        Osmformat.PrimitiveBlock block = Osmformat.PrimitiveBlock.newBuilder().setStringtable(strings)
                .addPrimitivegroup(Osmformat.PrimitiveGroup.newBuilder().addNodes(Osmformat.Node.newBuilder()
                        .setId(123).setLat(-399500000).setLon(1150500000).addKeys(1).addVals(2))).build();
        Osmformat.PrimitiveBlock decoded = Osmformat.PrimitiveBlock.parseFrom(block.toByteArray());
        assertEquals("\u5c0f\u4e94\u53f0", decoded.getStringtable().getS(2).toStringUtf8());
        assertEquals(-399500000L, decoded.getPrimitivegroup(0).getNodes(0).getLat());
    }
}
