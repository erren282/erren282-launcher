package org.jackhuang.hmcl.addon.meta;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/// Old Forge mods (1.7–1.12), Chinese ones especially, ship `mcmod.info` in GB18030 or with
/// a UTF-8 BOM; the reader used to reject or garble both.
public final class ForgeOldModMetadataTest {

    @Test
    public void plainUtf8IsKept() {
        String text = "[{\"modid\":\"demo\",\"name\":\"测试模组\"}]";
        assertEquals(text, ForgeOldModMetadata.decodeText(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void utf8BomIsStripped() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        String json = "[{\"modid\":\"demo\",\"name\":\"测试\"}]";
        byte[] bytes = concat(bom, json.getBytes(StandardCharsets.UTF_8));
        assertEquals(json, ForgeOldModMetadata.decodeText(bytes));
    }

    @Test
    public void gb18030ChineseIsDecoded() {
        String json = "[{\"modid\":\"demo\",\"name\":\"中文输入修复\"}]";
        byte[] gbBytes = json.getBytes(java.nio.charset.Charset.forName("GB18030"));
        assertFalse(new String(gbBytes, StandardCharsets.UTF_8).contains("中文输入修复"),
                "the fixture really is not UTF-8");
        assertEquals(json, ForgeOldModMetadata.decodeText(gbBytes));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = new byte[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}
