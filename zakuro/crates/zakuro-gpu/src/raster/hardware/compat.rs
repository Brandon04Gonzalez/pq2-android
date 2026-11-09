//! what a GPU lacks of Vulkan 1.3 that the renderer leans on, and what
//! stands in for it, so that phones whose drivers stop short of it draw on
//! the GPU all the same: the Mali GPUs of Kirin, Exynos, Tensor and most
//! MediaTek chips, Samsung's Xclipse, the PowerVR of Tensor G5 and the Helio
//! chips, Huawei's Maleoon, and the older Adreno of Snapdragon chips.
//!
//! - dynamic rendering, core or VK_KHR_dynamic_rendering, or else render
//!   passes, with framebuffers made as rendering needs them;
//! - synchronization2, core or VK_KHR_synchronization2, or else the same
//!   barriers in Vulkan 1.0's terms;
//! - the culling, depth and stencil state draws set as they go, core or
//!   VK_EXT_extended_dynamic_state, or else pipelines that hold it;
//! - a D24S8 depth format, or else D32S8, the values turned to and from
//!   floats on the way between guest memory and the GPU;
//! - depth clamping, or else depth clamped where the CPU places vertices;
//! - pipeline creation cache control, or else no looking in the cache
//!   without compiling.
//!
//! ZAKURO_VK_COMPAT, a list of passes, barriers, static, d32, noclamp and
//! nocache, or all, takes the fallbacks named on a GPU that has the real
//! thing, to try them out.

use std::collections::HashMap;
use std::sync::{Mutex, PoisonError};

use ash::vk;

/// how the device does something the renderer needs.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub(crate) enum Through {
    /// Vulkan 1.3 has it.
    #[default]
    Core,
    /// an extension on an older version has it.
    Extension,
    /// the renderer does without, the other way.
    Fallback,
}

/// who made the GPU, for logs and the few things done differently on one.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub(crate) enum Vendor {
    /// Adreno, in Snapdragon chips.
    Qualcomm,
    /// Mali and Immortalis, in Kirin, Exynos, Tensor G1 to G4 and MediaTek chips.
    Arm,
    /// Xclipse, AMD's design in Exynos chips.
    Samsung,
    /// PowerVR, in Tensor G5 and some MediaTek chips.
    Imagination,
    /// Maleoon, in later Kirin chips.
    Huawei,
    Amd,
    Nvidia,
    Intel,
    Apple,
    #[default]
    Other,
}

impl Vendor {
    fn of(id: u32, name: &str) -> Vendor {
        let name = name.to_ascii_lowercase();
        match id {
            0x5143 => Vendor::Qualcomm,
            0x13B5 => Vendor::Arm,
            0x144D => Vendor::Samsung,
            0x1010 => Vendor::Imagination,
            0x19E5 => Vendor::Huawei,
            0x1002 | 0x1022 => Vendor::Amd,
            0x10DE => Vendor::Nvidia,
            0x8086 => Vendor::Intel,
            0x106B => Vendor::Apple,
            _ if name.contains("adreno") => Vendor::Qualcomm,
            _ if name.contains("mali") || name.contains("immortalis") => Vendor::Arm,
            _ if name.contains("xclipse") => Vendor::Samsung,
            _ if name.contains("powervr") || name.contains("img ") => Vendor::Imagination,
            _ if name.contains("maleoon") => Vendor::Huawei,
            _ => Vendor::Other,
        }
    }

    pub(crate) fn name(self) -> &'static str {
        match self {
            Vendor::Qualcomm => "Qualcomm Adreno",
            Vendor::Arm => "Arm Mali",
            Vendor::Samsung => "Samsung Xclipse",
            Vendor::Imagination => "Imagination PowerVR",
            Vendor::Huawei => "Huawei Maleoon",
            Vendor::Amd => "AMD",
            Vendor::Nvidia => "NVIDIA",
            Vendor::Intel => "Intel",
            Vendor::Apple => "Apple",
            Vendor::Other => "other",
        }
    }
}

/// what the device has of what the renderer needs, and how.
#[derive(Clone, Copy, Default)]
pub(crate) struct Caps {
    /// the version of Vulkan the device is used at, at most 1.3.
    pub(crate) version: u32,
    pub(crate) vendor: Vendor,
    pub(crate) rendering: Through,
    pub(crate) sync2: Through,
    /// culling, depth and stencil state set as draws go.
    pub(crate) depth_state: Through,
    /// the depth and stencil format, D24S8 or D32S8, none when it has
    /// neither as a depth attachment that copies.
    pub(crate) depth_format: vk::Format,
    pub(crate) depth_clamp: bool,
    /// whether a pipeline can be asked for only if the cache has it.
    pub(crate) cache_control: bool,
}

/// the fallbacks ZAKURO_VK_COMPAT asks for even where the GPU does without
/// them.
#[derive(Debug, Clone, Copy, Default)]
struct Forced {
    passes: bool,
    barriers: bool,
    fixed: bool,
    d32: bool,
    no_clamp: bool,
    no_cache: bool,
}

fn forced() -> Forced {
    let value = std::env::var("ZAKURO_VK_COMPAT").unwrap_or_default().to_ascii_lowercase();
    let words: Vec<&str> = value.split([',', ' ', ';']).map(str::trim).filter(|word| !word.is_empty()).collect();
    let has = |word: &str| words.iter().any(|w| *w == word || *w == "all");
    Forced {
        passes: has("passes"),
        barriers: has("barriers"),
        fixed: has("static"),
        d32: has("d32"),
        no_clamp: has("noclamp"),
        no_cache: has("nocache"),
    }
}

/// the version of Vulkan instances are made for, the loader's up to 1.3,
/// kept for caps, which can't ask the instance. an instance made for 1.3
/// on a loader of 1.0, Android 8's, isn't made at all.
static INSTANCE_VERSION: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(vk::API_VERSION_1_3);

/// the version to make an instance for, see INSTANCE_VERSION.
pub(crate) fn instance_version(entry: &ash::Entry) -> u32 {
    // SAFETY: a query of the loader's
    let loader = unsafe { entry.try_enumerate_instance_version() }.ok().flatten().unwrap_or(vk::API_VERSION_1_0);
    let version = loader.min(vk::API_VERSION_1_3);
    INSTANCE_VERSION.store(version, std::sync::atomic::Ordering::Relaxed);
    version
}

/// writes what the GPU drawing the 3D is and how to the file
/// ZAKURO_GPU_REPORT names, for a launcher to show, the GPU's name on the
/// first line and caps' description on the second.
pub(crate) fn report(text: &str) {
    if let Some(path) = std::env::var_os("ZAKURO_GPU_REPORT").filter(|path| !path.is_empty()) {
        if let Err(error) = std::fs::write(&path, text) {
            log::warn!("could not write the GPU's report to {}, {error}", std::path::Path::new(&path).display());
        }
    }
}

/// whether a device extension is there.
fn has(extensions: &[vk::ExtensionProperties], name: &std::ffi::CStr) -> bool {
    extensions.iter().any(|e| e.extension_name_as_c_str() == Ok(name))
}

/// whether a depth format makes images the renderer draws into, copies and
/// blits or not.
fn depth_format_works(instance: &ash::Instance, physical: vk::PhysicalDevice, format: vk::Format) -> bool {
    let usage = vk::ImageUsageFlags::DEPTH_STENCIL_ATTACHMENT | vk::ImageUsageFlags::TRANSFER_SRC | vk::ImageUsageFlags::TRANSFER_DST;
    // SAFETY: queries on a GPU of the instance
    let (properties, image) = unsafe {
        (
            instance.get_physical_device_format_properties(physical, format),
            instance.get_physical_device_image_format_properties(
                physical,
                format,
                vk::ImageType::TYPE_2D,
                vk::ImageTiling::OPTIMAL,
                usage,
                vk::ImageCreateFlags::empty(),
            ),
        )
    };
    properties.optimal_tiling_features.contains(vk::FormatFeatureFlags::DEPTH_STENCIL_ATTACHMENT) && image.is_ok()
}

/// what a GPU has of what the renderer needs.
pub(crate) fn caps(instance: &ash::Instance, physical: vk::PhysicalDevice) -> Caps {
    let forced = forced();
    // SAFETY: queries on a GPU of the instance
    let (properties, features, extensions) = unsafe {
        (
            instance.get_physical_device_properties(physical),
            instance.get_physical_device_features(physical),
            instance.enumerate_device_extension_properties(physical).unwrap_or_default(),
        )
    };
    let name = properties.device_name_as_c_str().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let version = properties.api_version.min(INSTANCE_VERSION.load(std::sync::atomic::Ordering::Relaxed));
    let vendor = Vendor::of(properties.vendor_id, &name);
    let core13 = version >= vk::API_VERSION_1_3;
    // the features of the extensions on an older version, which
    // vkGetPhysicalDeviceFeatures2 tells from 1.1 on
    let (mut rendering_ext, mut sync2_ext, mut state_ext, mut cache_ext) = (false, false, false, false);
    if !core13 && version >= vk::API_VERSION_1_1 {
        let mut rendering = vk::PhysicalDeviceDynamicRenderingFeatures::default();
        let mut sync2 = vk::PhysicalDeviceSynchronization2Features::default();
        let mut state = vk::PhysicalDeviceExtendedDynamicStateFeaturesEXT::default();
        let mut cache = vk::PhysicalDevicePipelineCreationCacheControlFeatures::default();
        // dynamic rendering takes depth and stencil resolves, and those
        // render passes made the second way, both core from 1.2
        let rendering_there = has(&extensions, ash::khr::dynamic_rendering::NAME)
            && (version >= vk::API_VERSION_1_2
                || (has(&extensions, ash::khr::depth_stencil_resolve::NAME) && has(&extensions, ash::khr::create_renderpass2::NAME)));
        let sync2_there = has(&extensions, ash::khr::synchronization2::NAME);
        let state_there = has(&extensions, ash::ext::extended_dynamic_state::NAME);
        let cache_there = has(&extensions, ash::ext::pipeline_creation_cache_control::NAME);
        {
            let mut asked = vk::PhysicalDeviceFeatures2::default();
            if rendering_there {
                asked = asked.push_next(&mut rendering);
            }
            if sync2_there {
                asked = asked.push_next(&mut sync2);
            }
            if state_there {
                asked = asked.push_next(&mut state);
            }
            if cache_there {
                asked = asked.push_next(&mut cache);
            }
            // SAFETY: a chain of structures that live as long, of the
            // extensions the device has
            unsafe { instance.get_physical_device_features2(physical, &mut asked) };
        }
        rendering_ext = rendering_there && rendering.dynamic_rendering == vk::TRUE;
        sync2_ext = sync2_there && sync2.synchronization2 == vk::TRUE;
        state_ext = state_there && state.extended_dynamic_state == vk::TRUE;
        cache_ext = cache_there && cache.pipeline_creation_cache_control == vk::TRUE;
    }
    let through = |core: bool, extension: bool, forced: bool| match (forced, core, extension) {
        (true, ..) => Through::Fallback,
        (_, true, _) => Through::Core,
        (_, _, true) => Through::Extension,
        _ => Through::Fallback,
    };
    let d24s8 = !forced.d32 && depth_format_works(instance, physical, vk::Format::D24_UNORM_S8_UINT);
    let depth_format = if d24s8 {
        vk::Format::D24_UNORM_S8_UINT
    } else if depth_format_works(instance, physical, vk::Format::D32_SFLOAT_S8_UINT) {
        vk::Format::D32_SFLOAT_S8_UINT
    } else {
        vk::Format::UNDEFINED
    };
    Caps {
        version,
        vendor,
        rendering: through(core13, rendering_ext, forced.passes),
        sync2: through(core13, sync2_ext, forced.barriers),
        depth_state: through(core13, state_ext, forced.fixed),
        depth_format,
        depth_clamp: features.depth_clamp == vk::TRUE && !forced.no_clamp,
        cache_control: (core13 || cache_ext) && !forced.no_cache,
    }
}

impl Caps {
    /// whether the renderer draws on the device at all, Vulkan 1.1 and a
    /// depth format being all it can't do without.
    pub(crate) fn renders(&self) -> bool {
        self.version >= vk::API_VERSION_1_1 && self.depth_format != vk::Format::UNDEFINED
    }

    /// whether depth goes to the GPU as floats rather than 24-bit integers.
    pub(crate) fn float_depth(&self) -> bool {
        self.depth_format == vk::Format::D32_SFLOAT_S8_UINT
    }

    /// the device extensions the renderer's way of doing things takes.
    pub(crate) fn extensions(&self) -> Vec<&'static std::ffi::CStr> {
        let mut names = Vec::new();
        if self.rendering == Through::Extension {
            names.push(ash::khr::dynamic_rendering::NAME);
            if self.version < vk::API_VERSION_1_2 {
                names.extend([ash::khr::depth_stencil_resolve::NAME, ash::khr::create_renderpass2::NAME]);
            }
        }
        if self.sync2 == Through::Extension {
            names.push(ash::khr::synchronization2::NAME);
        }
        if self.depth_state == Through::Extension {
            names.push(ash::ext::extended_dynamic_state::NAME);
        }
        if self.cache_control && self.version < vk::API_VERSION_1_3 {
            names.push(ash::ext::pipeline_creation_cache_control::NAME);
        }
        names
    }

    /// what the device lacks and does the other way, for the log.
    pub(crate) fn describe(&self) -> String {
        let mut said = vec![format!(
            "{}, Vulkan {}.{}",
            self.vendor.name(),
            vk::api_version_major(self.version),
            vk::api_version_minor(self.version)
        )];
        let how = |what: &str, through: Through, fallback: &str| match through {
            Through::Core => None,
            Through::Extension => Some(format!("{what} by extension")),
            Through::Fallback => Some(fallback.to_owned()),
        };
        said.extend(how("dynamic rendering", self.rendering, "render passes"));
        said.extend(how("synchronization2", self.sync2, "Vulkan 1.0 barriers"));
        said.extend(how("dynamic depth state", self.depth_state, "depth state in the pipelines"));
        if self.float_depth() {
            said.push("D32S8 depth".to_owned());
        }
        if !self.depth_clamp {
            said.push("depth clamped on the CPU".to_owned());
        }
        if !self.cache_control {
            said.push("no cache control".to_owned());
        }
        said.join(", ")
    }
}

/// the attachments a render pass has, for the render passes made without
/// dynamic rendering.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) enum Attachments {
    Color,
    ColorDepth,
    Depth,
}

/// the render passes pipelines are made for and rendering begins, when the
/// device has no dynamic rendering: color, color and depth, then depth.
pub(crate) type Passes = [vk::RenderPass; 3];

impl Attachments {
    fn index(self) -> usize {
        match self {
            Attachments::Color => 0,
            Attachments::ColorDepth => 1,
            Attachments::Depth => 2,
        }
    }

    pub(crate) fn of(color: bool, depth: bool) -> Attachments {
        match (color, depth) {
            (true, false) => Attachments::Color,
            (true, true) => Attachments::ColorDepth,
            _ => Attachments::Depth,
        }
    }

    /// its render pass among passes.
    pub(crate) fn pass(self, passes: &Passes) -> vk::RenderPass {
        passes[self.index()]
    }
}

/// a render pass rendering keeps what attachments held and leaves them so,
/// all of them in the general layout, which images never leave.
///
/// SAFETY: a device of the caller's.
unsafe fn render_pass(device: &ash::Device, attachments: Attachments, color: vk::Format, depth: vk::Format) -> Result<vk::RenderPass, String> {
    let kept = |format: vk::Format| {
        vk::AttachmentDescription::default()
            .format(format)
            .samples(vk::SampleCountFlags::TYPE_1)
            .load_op(vk::AttachmentLoadOp::LOAD)
            .store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::LOAD)
            .stencil_store_op(vk::AttachmentStoreOp::STORE)
            .initial_layout(vk::ImageLayout::GENERAL)
            .final_layout(vk::ImageLayout::GENERAL)
    };
    let mut described = Vec::new();
    let color_reference = [vk::AttachmentReference::default().attachment(0).layout(vk::ImageLayout::GENERAL)];
    let depth_reference = vk::AttachmentReference::default()
        .attachment(if attachments == Attachments::Depth { 0 } else { 1 })
        .layout(vk::ImageLayout::GENERAL);
    let mut subpass = vk::SubpassDescription::default().pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS);
    if attachments != Attachments::Depth {
        described.push(kept(color));
        subpass = subpass.color_attachments(&color_reference);
    }
    if attachments != Attachments::Color {
        described.push(kept(depth));
        subpass = subpass.depth_stencil_attachment(&depth_reference);
    }
    let subpasses = [subpass];
    // what comes before and after is fenced off by the renderer's own
    // barriers, as it is with dynamic rendering
    let info = vk::RenderPassCreateInfo::default().attachments(&described).subpasses(&subpasses);
    device.create_render_pass(&info, None).map_err(|error| format!("could not create a render pass, {error}"))
}

/// the commands of Vulkan 1.3 the renderer records, through whichever way
/// the device has them.
pub(crate) struct Commands {
    device: ash::Device,
    pub(crate) caps: Caps,
    rendering: Option<ash::khr::dynamic_rendering::Device>,
    sync2: Option<ash::khr::synchronization2::Device>,
    depth_state: Option<ash::ext::extended_dynamic_state::Device>,
    /// the render passes, when rendering falls back on them.
    pub(crate) passes: Option<Passes>,
    /// the framebuffers made for them, by their views and size.
    framebuffers: Mutex<HashMap<(vk::RenderPass, vk::ImageView, vk::ImageView, u32, u32), vk::Framebuffer>>,
}

impl Commands {
    /// the commands for a device made with caps' extensions.
    pub(crate) fn new(instance: &ash::Instance, device: &ash::Device, caps: Caps, color: vk::Format) -> Result<Commands, String> {
        let passes = match caps.rendering {
            Through::Fallback => {
                let mut passes = [vk::RenderPass::null(); 3];
                for attachments in [Attachments::Color, Attachments::ColorDepth, Attachments::Depth] {
                    // SAFETY: the device is the caller's
                    passes[attachments.index()] = unsafe { render_pass(device, attachments, color, caps.depth_format)? };
                }
                Some(passes)
            }
            _ => None,
        };
        Ok(Commands {
            device: device.clone(),
            caps,
            rendering: (caps.rendering == Through::Extension).then(|| ash::khr::dynamic_rendering::Device::new(instance, device)),
            sync2: (caps.sync2 == Through::Extension).then(|| ash::khr::synchronization2::Device::new(instance, device)),
            depth_state: (caps.depth_state == Through::Extension).then(|| ash::ext::extended_dynamic_state::Device::new(instance, device)),
            passes,
            framebuffers: Mutex::new(HashMap::new()),
        })
    }

    /// records a barrier.
    ///
    /// SAFETY: commands is recording, outside rendering.
    pub(crate) unsafe fn barrier(&self, commands: vk::CommandBuffer, info: &vk::DependencyInfo) {
        match (self.caps.sync2, &self.sync2) {
            (Through::Core, _) => self.device.cmd_pipeline_barrier2(commands, info),
            (Through::Extension, Some(sync2)) => sync2.cmd_pipeline_barrier2(commands, info),
            _ => self.legacy_barrier(commands, info),
        }
    }

    /// the same barrier in Vulkan 1.0's terms, the stages of all of its
    /// barriers together.
    unsafe fn legacy_barrier(&self, commands: vk::CommandBuffer, info: &vk::DependencyInfo) {
        unsafe fn parts<'a, T>(count: u32, pointer: *const T) -> &'a [T] {
            if count == 0 || pointer.is_null() {
                &[]
            } else {
                std::slice::from_raw_parts(pointer, count as usize)
            }
        }
        let memory: &[vk::MemoryBarrier2] = parts(info.memory_barrier_count, info.p_memory_barriers);
        let buffers: &[vk::BufferMemoryBarrier2] = parts(info.buffer_memory_barrier_count, info.p_buffer_memory_barriers);
        let images: &[vk::ImageMemoryBarrier2] = parts(info.image_memory_barrier_count, info.p_image_memory_barriers);
        let (mut source, mut target) = (vk::PipelineStageFlags2::empty(), vk::PipelineStageFlags2::empty());
        let memory: Vec<vk::MemoryBarrier> = memory
            .iter()
            .map(|b| {
                (source, target) = (source | b.src_stage_mask, target | b.dst_stage_mask);
                vk::MemoryBarrier::default().src_access_mask(access(b.src_access_mask)).dst_access_mask(access(b.dst_access_mask))
            })
            .collect();
        let buffers: Vec<vk::BufferMemoryBarrier> = buffers
            .iter()
            .map(|b| {
                (source, target) = (source | b.src_stage_mask, target | b.dst_stage_mask);
                vk::BufferMemoryBarrier::default()
                    .src_access_mask(access(b.src_access_mask))
                    .dst_access_mask(access(b.dst_access_mask))
                    .src_queue_family_index(b.src_queue_family_index)
                    .dst_queue_family_index(b.dst_queue_family_index)
                    .buffer(b.buffer)
                    .offset(b.offset)
                    .size(b.size)
            })
            .collect();
        let images: Vec<vk::ImageMemoryBarrier> = images
            .iter()
            .map(|b| {
                (source, target) = (source | b.src_stage_mask, target | b.dst_stage_mask);
                vk::ImageMemoryBarrier::default()
                    .src_access_mask(access(b.src_access_mask))
                    .dst_access_mask(access(b.dst_access_mask))
                    .old_layout(b.old_layout)
                    .new_layout(b.new_layout)
                    .src_queue_family_index(b.src_queue_family_index)
                    .dst_queue_family_index(b.dst_queue_family_index)
                    .image(b.image)
                    .subresource_range(b.subresource_range)
            })
            .collect();
        self.device.cmd_pipeline_barrier(
            commands,
            stages(source, vk::PipelineStageFlags::TOP_OF_PIPE),
            stages(target, vk::PipelineStageFlags::BOTTOM_OF_PIPE),
            info.dependency_flags,
            &memory,
            &buffers,
            &images,
        );
    }

    /// records a timestamp once everything before is done.
    ///
    /// SAFETY: commands is recording and the query is the pool's.
    pub(crate) unsafe fn timestamp(&self, commands: vk::CommandBuffer, pool: vk::QueryPool, query: u32) {
        match (self.caps.sync2, &self.sync2) {
            (Through::Core, _) => self.device.cmd_write_timestamp2(commands, vk::PipelineStageFlags2::ALL_COMMANDS, pool, query),
            (Through::Extension, Some(sync2)) => sync2.cmd_write_timestamp2(commands, vk::PipelineStageFlags2::ALL_COMMANDS, pool, query),
            _ => self.device.cmd_write_timestamp(commands, vk::PipelineStageFlags::BOTTOM_OF_PIPE, pool, query),
        }
    }

    /// begins rendering into a color view, a depth and stencil one or both,
    /// over area from the corner, keeping what they held, all of them in the
    /// general layout.
    ///
    /// SAFETY: commands is recording, outside rendering, and the views are
    /// at least area's size, of the renderer's color and depth formats.
    pub(crate) unsafe fn begin_rendering(
        &self,
        commands: vk::CommandBuffer,
        extent: vk::Extent2D,
        color: Option<vk::ImageView>,
        depth: Option<vk::ImageView>,
    ) -> Result<(), String> {
        let area = vk::Rect2D { offset: vk::Offset2D { x: 0, y: 0 }, extent };
        if let Some(passes) = &self.passes {
            let pass = Attachments::of(color.is_some(), depth.is_some()).pass(passes);
            let framebuffer = self.framebuffer(pass, color, depth, extent)?;
            let info = vk::RenderPassBeginInfo::default().render_pass(pass).framebuffer(framebuffer).render_area(area);
            self.device.cmd_begin_render_pass(commands, &info, vk::SubpassContents::INLINE);
            return Ok(());
        }
        let attachment = |view: vk::ImageView| {
            vk::RenderingAttachmentInfo::default()
                .image_view(view)
                .image_layout(vk::ImageLayout::GENERAL)
                .load_op(vk::AttachmentLoadOp::LOAD)
                .store_op(vk::AttachmentStoreOp::STORE)
        };
        let colors: Vec<vk::RenderingAttachmentInfo> = color.into_iter().map(attachment).collect();
        let depth_attachment = depth.map(attachment);
        let mut info = vk::RenderingInfo::default().render_area(area).layer_count(1).color_attachments(&colors);
        if let Some(depth_attachment) = &depth_attachment {
            info = info.depth_attachment(depth_attachment).stencil_attachment(depth_attachment);
        }
        match &self.rendering {
            Some(rendering) => rendering.cmd_begin_rendering(commands, &info),
            None => self.device.cmd_begin_rendering(commands, &info),
        }
        Ok(())
    }

    /// ends the rendering begun last.
    ///
    /// SAFETY: commands is recording inside rendering it began.
    pub(crate) unsafe fn end_rendering(&self, commands: vk::CommandBuffer) {
        if self.passes.is_some() {
            self.device.cmd_end_render_pass(commands);
        } else {
            match &self.rendering {
                Some(rendering) => rendering.cmd_end_rendering(commands),
                None => self.device.cmd_end_rendering(commands),
            }
        }
    }

    /// the framebuffer of a render pass over views, made the first time.
    unsafe fn framebuffer(
        &self,
        pass: vk::RenderPass,
        color: Option<vk::ImageView>,
        depth: Option<vk::ImageView>,
        extent: vk::Extent2D,
    ) -> Result<vk::Framebuffer, String> {
        let (color, depth) = (color.unwrap_or_default(), depth.unwrap_or_default());
        let key = (pass, color, depth, extent.width, extent.height);
        let mut framebuffers = self.framebuffers.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(&framebuffer) = framebuffers.get(&key) {
            return Ok(framebuffer);
        }
        let views: Vec<vk::ImageView> = [color, depth].into_iter().filter(|view| *view != vk::ImageView::null()).collect();
        let info = vk::FramebufferCreateInfo::default()
            .render_pass(pass)
            .attachments(&views)
            .width(extent.width)
            .height(extent.height)
            .layers(1);
        let framebuffer = self.device.create_framebuffer(&info, None).map_err(|error| format!("could not create a framebuffer, {error}"))?;
        framebuffers.insert(key, framebuffer);
        Ok(framebuffer)
    }

    /// lets go of the framebuffers over a view about to go, which the GPU
    /// is done with.
    pub(crate) fn forget_view(&self, view: vk::ImageView) {
        // a null view stands for no attachment in the keys
        if self.passes.is_none() || view == vk::ImageView::null() {
            return;
        }
        let mut framebuffers = self.framebuffers.lock().unwrap_or_else(PoisonError::into_inner);
        framebuffers.retain(|&(_, color, depth, ..), framebuffer| {
            let keep = color != view && depth != view;
            if !keep {
                // SAFETY: the GPU is done with the view, so with this
                unsafe { self.device.destroy_framebuffer(*framebuffer, None) };
            }
            keep
        });
    }

    /// whether draws set their culling, depth and stencil state as they go.
    pub(crate) fn dynamic_depth_state(&self) -> bool {
        self.caps.depth_state != Through::Fallback
    }

    /// sets the culling, depth and stencil state a draw goes with, when
    /// draws set it, see dynamic_depth_state.
    ///
    /// SAFETY: commands is recording.
    #[allow(clippy::too_many_arguments)]
    pub(crate) unsafe fn set_depth_state(
        &self,
        commands: vk::CommandBuffer,
        cull: vk::CullModeFlags,
        depth_test: bool,
        depth_compare: vk::CompareOp,
        depth_write: bool,
        stencil_test: bool,
        [fail, pass, depth_fail]: [vk::StencilOp; 3],
        stencil_compare: vk::CompareOp,
    ) {
        let face = vk::StencilFaceFlags::FRONT_AND_BACK;
        match (self.caps.depth_state, &self.depth_state) {
            (Through::Core, _) => {
                let device = &self.device;
                device.cmd_set_cull_mode(commands, cull);
                device.cmd_set_depth_test_enable(commands, depth_test);
                device.cmd_set_depth_compare_op(commands, depth_compare);
                device.cmd_set_depth_write_enable(commands, depth_write);
                device.cmd_set_stencil_test_enable(commands, stencil_test);
                device.cmd_set_stencil_op(commands, face, fail, pass, depth_fail, stencil_compare);
            }
            (Through::Extension, Some(state)) => {
                state.cmd_set_cull_mode(commands, cull);
                state.cmd_set_depth_test_enable(commands, depth_test);
                state.cmd_set_depth_compare_op(commands, depth_compare);
                state.cmd_set_depth_write_enable(commands, depth_write);
                state.cmd_set_stencil_test_enable(commands, stencil_test);
                state.cmd_set_stencil_op(commands, face, fail, pass, depth_fail, stencil_compare);
            }
            _ => {}
        }
    }

    /// destroys the render passes and framebuffers.
    ///
    /// SAFETY: the GPU is done with them.
    pub(crate) unsafe fn destroy(&self) {
        let mut framebuffers = self.framebuffers.lock().unwrap_or_else(PoisonError::into_inner);
        for (_, framebuffer) in framebuffers.drain() {
            self.device.destroy_framebuffer(framebuffer, None);
        }
        for pass in self.passes.iter().flatten() {
            self.device.destroy_render_pass(*pass, None);
        }
    }
}

/// Vulkan 1.0's stages for synchronization2's, or else the one given.
fn stages(flags: vk::PipelineStageFlags2, none: vk::PipelineStageFlags) -> vk::PipelineStageFlags {
    use vk::PipelineStageFlags as Old;
    use vk::PipelineStageFlags2 as New;
    // the stages 1.0 has keep their bits, less those of tessellation and
    // geometry shaders, which the device was not made with
    let kept = (flags.as_raw() & 0x1_FFFF) as u32 & !(Old::TESSELLATION_CONTROL_SHADER | Old::TESSELLATION_EVALUATION_SHADER | Old::GEOMETRY_SHADER).as_raw();
    let mut old = Old::from_raw(kept);
    if flags.intersects(New::COPY | New::RESOLVE | New::BLIT | New::CLEAR) {
        old |= Old::TRANSFER;
    }
    if flags.intersects(New::INDEX_INPUT | New::VERTEX_ATTRIBUTE_INPUT) {
        old |= Old::VERTEX_INPUT;
    }
    if flags.contains(New::PRE_RASTERIZATION_SHADERS) {
        old |= Old::VERTEX_SHADER;
    }
    if old.is_empty() {
        none
    } else {
        old
    }
}

/// Vulkan 1.0's access flags for synchronization2's.
fn access(flags: vk::AccessFlags2) -> vk::AccessFlags {
    use vk::AccessFlags as Old;
    use vk::AccessFlags2 as New;
    let mut old = Old::from_raw((flags.as_raw() & 0x1_FFFF) as u32);
    if flags.intersects(New::SHADER_SAMPLED_READ | New::SHADER_STORAGE_READ) {
        old |= Old::SHADER_READ;
    }
    if flags.contains(New::SHADER_STORAGE_WRITE) {
        old |= Old::SHADER_WRITE;
    }
    old
}

/// a 24-bit depth value as the float a D32S8 image holds for it, the
/// largest one 1.0, as a clear to it is, worked out in doubles so that each
/// comes back the same, see float_to_depth.
pub(crate) fn depth_to_float(value: u32) -> f32 {
    ((value & 0xFF_FFFF) as f64 / 16_777_215.0) as f32
}

/// a float of a D32S8 image as the 24-bit value a D24S8 one would hold.
pub(crate) fn float_to_depth(depth: f32) -> u32 {
    (depth.clamp(0.0, 1.0) as f64 * 16_777_215.0).round() as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    /// every 24-bit depth survives the trip to a float and back, so a
    /// buffer the GPU did not draw over comes back as memory had it.
    #[test]
    fn depth_goes_to_floats_and_back_unchanged() {
        for value in 0..=0xFF_FFFFu32 {
            assert_eq!(float_to_depth(depth_to_float(value)), value, "{value:#x}");
        }
        assert_eq!(depth_to_float(0xFF_FFFF), 1.0);
        assert_eq!(depth_to_float(0), 0.0);
    }

    #[test]
    fn synchronization2_stages_map_to_old_ones() {
        use vk::PipelineStageFlags as Old;
        use vk::PipelineStageFlags2 as New;
        let raw = |flags: Old| flags.as_raw();
        assert_eq!(raw(stages(New::ALL_COMMANDS, Old::TOP_OF_PIPE)), raw(Old::ALL_COMMANDS));
        assert_eq!(raw(stages(New::NONE, Old::TOP_OF_PIPE)), raw(Old::TOP_OF_PIPE));
        assert_eq!(raw(stages(New::NONE, Old::BOTTOM_OF_PIPE)), raw(Old::BOTTOM_OF_PIPE));
        assert_eq!(raw(stages(New::COPY | New::HOST, Old::TOP_OF_PIPE)), raw(Old::TRANSFER | Old::HOST));
        assert_eq!(raw(stages(New::COMPUTE_SHADER, Old::TOP_OF_PIPE)), raw(Old::COMPUTE_SHADER));
        assert_eq!(
            access(vk::AccessFlags2::MEMORY_READ | vk::AccessFlags2::SHADER_STORAGE_WRITE).as_raw(),
            (vk::AccessFlags::MEMORY_READ | vk::AccessFlags::SHADER_WRITE).as_raw()
        );
    }

    #[test]
    fn vendors_are_told_by_id_or_name() {
        assert_eq!(Vendor::of(0x5143, "Adreno (TM) 740"), Vendor::Qualcomm);
        assert_eq!(Vendor::of(0x13B5, "Mali-G78"), Vendor::Arm);
        assert_eq!(Vendor::of(0, "Mali-G57 MC2"), Vendor::Arm);
        assert_eq!(Vendor::of(0x144D, "Samsung Xclipse 940"), Vendor::Samsung);
        assert_eq!(Vendor::of(0x1010, "PowerVR D-Series DXT-48-1536"), Vendor::Imagination);
        assert_eq!(Vendor::of(0, "Maleoon 910"), Vendor::Huawei);
    }
}
