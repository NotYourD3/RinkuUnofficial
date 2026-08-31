package de.keksuccino.rinku;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import de.keksuccino.rinku.example.ExampleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;

// 这个类就对应高版本的 RinkuExampleMod，只是名称变成 ClientProxy（因为 1.7.10 习惯用代理）
public class ClientProxy extends CommonProxy {

    // 按键：对应高版本中的 KEY_MAPPING
    public static final KeyBinding KEY_OPEN_BROWSER =
        new KeyBinding("key.rinku.openBrowser", Keyboard.KEY_F12, "key.categories.rinku");

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        // 高版本在构造时 new RinkuExampleMod 做了注册，这里等价：
        ClientRegistry.registerKeyBinding(KEY_OPEN_BROWSER);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        // 将自己注册到事件总线，相当于高版本的 NeoForge.EVENT_BUS.addListener
        MinecraftForge.EVENT_BUS.register(this);
        // 有些 Tick 事件需要注册到 FML 总线，为了保险两个都挂上（高版本 ClientTickEvent 走的是 NeoForge 总线，这里用 Forge 即可）
        // 但 1.7.10 的 TickEvent.ClientTickEvent 必须用 FMLCommonHandler 总线，所以这行不能省：
        FMLCommonHandler.instance().bus().register(this);
    }

    // 高版本中 onTick(ClientTickEvent.Post) 的等价实现
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        // 对应高版本的 KEY_MAPPING.isDown()，但在 1.7.10 用 isPressed() 更适合（防止连发）
        // 如果你想完全和 isDown() 一样，改成 KEY_OPEN_BROWSER.getIsKeyPressed()，但推荐 isPressed()
        if (KEY_OPEN_BROWSER.isPressed() && !(mc.currentScreen instanceof ExampleScreen)) {
            mc.displayGuiScreen(new ExampleScreen(new ChatComponentText("Rinku Browser")));
        }
    }
}
