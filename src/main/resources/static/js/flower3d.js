/**
 * 鲜花立体展示（Three.js + 真实 glTF 模型）：把 CC0 的花束模型放进影棚里，
 * 可以环绕、拉近看清花瓣与叶脉的细节。
 *
 * 模型：/models/GlassVaseFlowers.glb —— Khronos glTF-Sample-Assets，CC0-1.0 公共领域，
 * 带 KHR_materials_transmission / volume 的玻璃花瓶与整束鲜花。
 * 「哪个品类用哪个 GLB」不写死在这里，由 media_model_map 表 + /api/media/models 下发
 * （见 /js/stage3d.js 与 MediaController），加模型只要往 static/models/ 丢文件并插一行映射。
 *
 * 立体感的关键不是模型本身，而是环境光：RoomEnvironment 经 PMREM 生成的 IBL
 * 让花瓣有柔和的高光与反射，玻璃瓶才会折射；只打平行光会立刻显得塑料。
 *
 * 取景一律走「整圈包围盒 → 平移到原点 → 按视口比例推相机距离」：
 * 烘焙模型的顶点常常偏心，直接按高度取景会让花束跑到画面右下角。
 *
 * 用法：huFlower3D.mount(el, { model: '/models/x.glb', preset: 'full', capture: true })
 * WebGL 不可用返回 null；模型加载失败派发 flower3d:error，页面可重试或回落 2D 图集。
 * 模块加载完派发 huFlower3DReady。
 */
import * as THREE from '/js/vendor/three/three.module.min.js';
import { GLTFLoader } from '/js/vendor/three/examples/jsm/loaders/GLTFLoader.js';
import { RoomEnvironment } from '/js/vendor/three/examples/jsm/environments/RoomEnvironment.js';

const TARGET_HEIGHT = 1.8;    // 模型归一化后的世界高度
const FIT_MARGIN = 1.06;      // 包围盒外扩比例，避免花瓣贴着画面边缘
const DEFAULT_SPIN = 0.32;    // 自动旋转角速度（弧度/秒）

/**
 * 相机预设：targetY 是注视点高度（相对模型高度的比例），radius/fit 是相对取景距离的倍数。
 * anchor:'center' 表示注视点取包围盒中心——全景必须正中，不能靠手填比例猜。
 * 特写预设把注视点抬到花头位置，用户拉近时才不会转着空花瓶打转。
 */
const PRESETS = {
    full: { label: '全景', anchor: 'center', radius: 1.0, azimuth: 0.35, polar: 1.32 },
    head: { label: '花头特写', targetY: 0.82, radius: 0.44, azimuth: 0.1, polar: 1.42 },
    leaf: { label: '叶脉质感', targetY: 0.52, radius: 0.4, azimuth: 2.1, polar: 1.5 },
    top: { label: '俯视', targetY: 0.5, radius: 0.78, azimuth: 0.6, polar: 0.62 },
    vase: { label: '玻璃瓶', targetY: 0.16, radius: 0.5, azimuth: -0.8, polar: 1.5 }
};

/** 调试视图：shaded 是成品质感，clay 只看体量与光影，wire 看布线密度 */
const RENDER_MODES = {
    shaded: { label: '材质', icon: 'fa-cube' },
    clay: { label: '素模', icon: 'fa-flask-o' },
    wire: { label: '线框', icon: 'fa-i-cursor' }
};

export { PRESETS, RENDER_MODES };

export function mount(el, options) {
    const opts = Object.assign({
        model: null, preset: 'full', autoRotate: true, spinSpeed: DEFAULT_SPIN, capture: false
    }, options || {});
    if (!opts.model) return null;

    // 同一个容器重复 mount（失败重试）要把上一次的失败态擦干净
    el.classList.remove('is-ready', 'is-unavailable');
    while (el.firstChild) el.removeChild(el.firstChild);

    let renderer;
    try {
        renderer = new THREE.WebGLRenderer({
            antialias: true, alpha: true,
            // 截图要在同一帧里把画布读成 PNG，不保留缓冲区就会拿到空白图
            preserveDrawingBuffer: !!opts.capture
        });
    } catch (err) {
        return null;
    }
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.02;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    Object.assign(renderer.domElement.style, { display: 'block', width: '100%', height: '100%', touchAction: 'none' });
    el.appendChild(renderer.domElement);

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(32, 1, 0.02, 60);
    const modelRoot = new THREE.Group();
    scene.add(modelRoot);

    // 影棚 IBL：花瓣与玻璃的质感全靠它
    const pmrem = new THREE.PMREMGenerator(renderer);
    const envScene = new RoomEnvironment();
    scene.environment = pmrem.fromScene(envScene, 0.04).texture;
    envScene.traverse(node => {
        if (node.geometry) node.geometry.dispose();
        if (node.material) node.material.dispose();
    });

    const key = new THREE.DirectionalLight(0xffffff, 2.1);
    key.castShadow = true;
    key.shadow.mapSize.set(1024, 1024);
    key.shadow.camera.near = 0.2;
    key.shadow.camera.far = 12;
    key.shadow.bias = -0.0018;
    key.shadow.radius = 3;
    scene.add(key, key.target);
    const fill = new THREE.DirectionalLight(0xdfeae4, 0.7);
    fill.position.set(-3.2, 1.6, -2.4);
    scene.add(fill);

    const ground = new THREE.Mesh(new THREE.PlaneGeometry(8, 8),
        new THREE.ShadowMaterial({ opacity: 0.3 }));
    ground.rotation.x = -Math.PI / 2;
    ground.receiveShadow = true;
    scene.add(ground);

    let box = new THREE.Box3();
    let sphere = new THREE.Sphere();
    let fitDistance = 4;
    let preset = PRESETS[opts.preset] ? opts.preset : 'full';
    let azimuth = 0.35, polar = 1.32, radius = 4;
    let targetAzimuth = azimuth, targetPolar = polar, targetRadius = 4;
    let spinSpeed = prefersReducedMotion() ? 0
        : (isFinite(opts.spinSpeed) ? Math.max(0, Math.min(1.2, opts.spinSpeed)) : DEFAULT_SPIN);
    const initialSpin = spinSpeed;
    let renderMode = 'shaded';
    let ready = false, disposed = false, raf = 0;
    let onscreen = true, hiddentab = false, visible = true;
    let dragging = false;

    // 双指缩放：移动端两个 pointer 同时按下时按指间距变化推镜距
    const pointers = new Map();
    let pinchBase = 0, pinchRadius = 0;

    const originals = new Map();      // mesh uuid → 原始材质，切回 shaded 时逐项还原
    const clayMat = new THREE.MeshStandardMaterial({ color: 0xd7dce4, roughness: 0.78, metalness: 0 });
    const wireMat = new THREE.MeshBasicMaterial({ color: 0x0f7b63, wireframe: true });

    new GLTFLoader().load(opts.model, gltf => {
        if (disposed) return;
        const model = gltf.scene;
        model.traverse(node => {
            if (!node.isMesh) return;
            node.castShadow = true;
            node.receiveShadow = true;
            const material = node.material;
            if (material && material.isMeshStandardMaterial) {
                // 扫描/烘焙模型常带 0 粗糙度默认值，稍微压一下就成塑料了
                material.envMapIntensity = 1.15;
            }
            originals.set(node.uuid, material);
        });
        modelRoot.add(model);
        normalize();
        size();
        ready = true;
        applyPreset(preset, true);
        el.classList.add('is-ready');
        el.dispatchEvent(new CustomEvent('flower3d:ready', { detail: controller.state() }));
        start();
    }, xhr => {
        if (!xhr) return;
        // 服务端 gzip 或没有 Content-Length 时 total 为 0，页面按「已下载 KB」显示
        el.dispatchEvent(new CustomEvent('flower3d:progress', {
            detail: {
                pct: xhr.total ? Math.round(xhr.loaded / xhr.total * 100) : 0,
                loaded: xhr.loaded || 0,
                total: xhr.total || 0
            }
        }));
    }, err => {
        if (disposed) return;
        console.warn('立体模型加载失败，回落平面图集：', err && err.message ? err.message : err);
        el.classList.add('is-unavailable');
        el.dispatchEvent(new CustomEvent('flower3d:error', {
            detail: { message: err && err.message ? err.message : '模型加载失败', model: opts.model }
        }));
        controller.dispose();
    });

    function prefersReducedMotion() {
        return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
    }

    /** 把模型缩放到固定高度、水平居中并坐到地面上，之后所有取景都基于这个统一尺度 */
    function normalize() {
        box = new THREE.Box3().setFromObject(modelRoot);
        const sizeVec = box.getSize(new THREE.Vector3());
        const center = box.getCenter(new THREE.Vector3());
        const scale = TARGET_HEIGHT / Math.max(sizeVec.y, 0.001);
        modelRoot.scale.setScalar(scale);
        modelRoot.position.set(-center.x * scale, -box.min.y * scale, -center.z * scale);
        box = new THREE.Box3().setFromObject(modelRoot);
        // 整圈包围盒：含基座与向外散开的花头，取景半径按它算才不会把花裁掉
        sphere = box.getBoundingSphere(new THREE.Sphere());
        ground.position.y = 0.001;
        const span = Math.max(sphere.radius, 0.6) * 3;
        const cam = key.shadow.camera;
        cam.left = -span; cam.right = span; cam.top = span; cam.bottom = -span;
        cam.updateProjectionMatrix();
    }

    /**
     * 按视口比例推相机距离：取横/竖半视场角里更紧的那个，让包围球刚好被框住。
     * 容器从窄变宽（或旋屏）时张角变了，所以 size() 里要重算并同步当前镜距。
     */
    function updateFit() {
        const vFov = THREE.MathUtils.degToRad(camera.fov) / 2;
        const hFov = Math.atan(Math.tan(vFov) * Math.max(0.2, camera.aspect));
        const previous = fitDistance;
        fitDistance = Math.max(1.2, sphere.radius / Math.sin(Math.min(vFov, hFov)) * FIT_MARGIN);
        if (previous > 0 && ready) {
            const ratio = fitDistance / previous;
            targetRadius *= ratio;
            radius *= ratio;
        }
        camera.far = Math.max(60, fitDistance * 6);
        camera.updateProjectionMatrix();
    }

    /** 预设注视点：全景锁包围盒中心（保证画面正中），其余按高度比例抬到花头位置 */
    function presetFocus(name) {
        const p = PRESETS[name];
        if (!p) return new THREE.Vector3(0, TARGET_HEIGHT * 0.5, 0);
        if (p.anchor === 'center') return new THREE.Vector3(0, sphere.center.y, 0);
        return new THREE.Vector3(0, TARGET_HEIGHT * p.targetY, 0);
    }

    function applyPreset(name, immediate) {
        const p = PRESETS[name];
        if (!p) return;
        preset = name;
        targetAzimuth = p.azimuth;
        targetPolar = p.polar;
        targetRadius = fitDistance * p.radius;
        if (immediate) {
            azimuth = targetAzimuth;
            polar = targetPolar;
            radius = targetRadius;
        }
    }

    function size() {
        const rect = el.getBoundingClientRect();
        const width = Math.max(1, rect.width);
        const height = Math.max(1, rect.height);
        renderer.setSize(width, height, false);
        camera.aspect = width / height;
        camera.updateProjectionMatrix();
        updateFit();
    }

    const resizeObserver = new ResizeObserver(size);
    resizeObserver.observe(el);
    const visibility = new IntersectionObserver(entries => {
        onscreen = entries.some(entry => entry.isIntersecting);
        syncActive();
    }, { threshold: 0.05 });
    visibility.observe(el);
    // 切到别的标签页也要停 rAF，否则后台仍每帧渲染吃 GPU
    function onDocVisibility() {
        hiddentab = document.hidden;
        syncActive();
    }
    document.addEventListener('visibilitychange', onDocVisibility);
    function syncActive() {
        visible = onscreen && !hiddentab;
        if (visible) start(); else stop();
    }

    el.addEventListener('pointerdown', e => {
        pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
        dragging = true;
        if (pointers.size === 2) {
            pinchBase = pointerSpan();
            pinchRadius = targetRadius;
        }
        el.classList.add('is-grabbing');
    });
    window.addEventListener('pointermove', e => {
        const prev = pointers.get(e.pointerId);
        if (!prev) return;
        const dx = e.clientX - prev.x;
        const dy = e.clientY - prev.y;
        prev.x = e.clientX;
        prev.y = e.clientY;
        if (pointers.size >= 2) {
            const span = pointerSpan();
            if (pinchBase > 0 && span > 0) {
                // 手指张开 span 变大 → 镜距变近，比例换算比累计增量更跟手
                clampRadius(pinchRadius * (pinchBase / span));
            }
            return;
        }
        if (!dragging) return;
        targetAzimuth -= dx * 0.0075;
        targetPolar = Math.max(0.22, Math.min(1.62, targetPolar - dy * 0.006));
    });
    window.addEventListener('pointerup', releasePointer);
    window.addEventListener('pointercancel', releasePointer);

    function releasePointer(e) {
        pointers.delete(e.pointerId);
        if (pointers.size < 2) {
            pinchBase = 0;
        }
        if (pointers.size === 0) {
            dragging = false;
            el.classList.remove('is-grabbing');
        }
    }

    function pointerSpan() {
        const list = Array.from(pointers.values());
        if (list.length < 2) return 0;
        return Math.max(1, Math.hypot(list[0].x - list[1].x, list[0].y - list[1].y));
    }

    function clampRadius(value) {
        targetRadius = Math.max(fitDistance * 0.26, Math.min(fitDistance * 1.7, value));
    }

    el.addEventListener('wheel', e => {
        e.preventDefault();
        clampRadius(targetRadius + (e.deltaY > 0 ? fitDistance * 0.06 : -fitDistance * 0.06));
    }, { passive: false });
    el.addEventListener('dblclick', () => {
        applyPreset('full');
        targetZoomReset();
    });

    function targetZoomReset() {
        targetRadius = fitDistance * PRESETS[preset].radius;
    }

    const clock = new THREE.Clock();
    const focusPoint = new THREE.Vector3();
    const loop = () => {
        raf = requestAnimationFrame(loop);
        if (!visible || !ready) return;
        const delta = Math.min(0.05, clock.getDelta());
        if (!dragging && spinSpeed > 0) targetAzimuth += delta * spinSpeed;
        azimuth += (targetAzimuth - azimuth) * 0.12;
        polar += (targetPolar - polar) * 0.12;
        radius += (targetRadius - radius) * 0.12;
        focusPoint.copy(presetFocus(preset));
        key.position.set(focusPoint.x + 2.2, focusPoint.y + 3.4, focusPoint.z + 2.6);
        key.target.position.copy(focusPoint);
        camera.position.set(
            focusPoint.x + radius * Math.sin(polar) * Math.sin(azimuth),
            focusPoint.y + radius * Math.cos(polar),
            focusPoint.z + radius * Math.sin(polar) * Math.cos(azimuth));
        camera.lookAt(focusPoint);
        renderer.render(scene, camera);
    };
    // 首帧不预跑：模型没就绪时 start() 也没东西可画，等 ready 回调
    function start() {
        if (raf || disposed || !ready) return;
        clock.getDelta();
        raf = requestAnimationFrame(loop);
    }
    function stop() {
        if (!raf) return;
        cancelAnimationFrame(raf);
        raf = 0;
    }

    function applyRenderMode(mode) {
        modelRoot.traverse(node => {
            if (!node.isMesh) return;
            const original = originals.get(node.uuid);
            if (!original) return;
            if (mode === 'shaded') {
                node.material = original;
            } else if (mode === 'wire') {
                node.material = wireMat;
            } else {
                node.material = clayMat;
            }
        });
    }

    const controller = {
        setPreset(name) {
            if (!PRESETS[name]) return;
            applyPreset(name);
            el.dispatchEvent(new CustomEvent('flower3d:change', { detail: controller.state() }));
        },
        /** 兼容旧调用：只开关旋转，速度用上一次的值或默认档 */
        setAutoRotate(on) {
            if (on) spinSpeed = spinSpeed > 0 ? spinSpeed : DEFAULT_SPIN;
            else spinSpeed = 0;
        },
        setSpinSpeed(value) {
            const v = Number(value);
            spinSpeed = isFinite(v) ? Math.max(0, Math.min(1.2, v)) : 0;
        },
        setRenderMode(mode) {
            if (!RENDER_MODES[mode]) return;
            renderMode = mode;
            applyRenderMode(mode);
        },
        /** 键盘可达：方向键环绕、上下键俯仰（由页面绑定，见 stage3d.js） */
        orbit(deltaAzimuth, deltaPolar) {
            targetAzimuth += Number(deltaAzimuth) || 0;
            targetPolar = Math.max(0.22, Math.min(1.62, targetPolar + (Number(deltaPolar) || 0)));
        },
        zoom(step) {
            clampRadius(targetRadius + (Number(step) || 0) * fitDistance);
        },
        /** 当前镜距相对全景的比例，页面上换算成「远近 42%」这类可读文案 */
        distancePercent() {
            if (!fitDistance) return 100;
            const span = fitDistance * 1.7 - fitDistance * 0.26;
            return Math.round((1 - (targetRadius - fitDistance * 0.26) / span) * 100);
        },
        reset() {
            applyPreset('full');
            spinSpeed = prefersReducedMotion() ? 0 : initialSpin;
        },
        /** 导出当前视角 PNG：同步渲染一帧再读画布，异步读会拿到已清空的缓冲区 */
        capture() {
            if (!ready) return null;
            focusPoint.copy(presetFocus(preset));
            camera.position.set(
                focusPoint.x + radius * Math.sin(polar) * Math.sin(azimuth),
                focusPoint.y + radius * Math.cos(polar),
                focusPoint.z + radius * Math.sin(polar) * Math.cos(azimuth));
            camera.lookAt(focusPoint);
            renderer.render(scene, camera);
            try {
                return {
                    dataUrl: renderer.domElement.toDataURL('image/png'),
                    width: renderer.domElement.width,
                    height: renderer.domElement.height
                };
            } catch (err) {
                // 画布被跨域贴图污染时浏览器会拒绝导出，这里交回页面提示
                return null;
            }
        },
        state() {
            const spinning = spinSpeed > 0;
            return {
                preset: preset,
                label: PRESETS[preset].label,
                spinning: spinning,
                spinSpeed: spinSpeed,
                ready: ready,
                mode: renderMode,
                modeLabel: RENDER_MODES[renderMode].label,
                distance: controller.distancePercent(),
                model: opts.model
            };
        },
        dispose() {
            disposed = true;
            stop();
            resizeObserver.disconnect();
            visibility.disconnect();
            document.removeEventListener('visibilitychange', onDocVisibility);
            clayMat.dispose();
            wireMat.dispose();
            scene.traverse(node => {
                if (node.geometry) node.geometry.dispose();
                if (node.material) {
                    Object.values(node.material).forEach(value => {
                        if (value && value.isTexture) value.dispose();
                    });
                    node.material.dispose();
                }
            });
            originals.clear();
            pmrem.dispose();
            renderer.dispose();
            if (renderer.domElement.parentNode === el) el.removeChild(renderer.domElement);
        }
    };
    return controller;
}

export const supported = (() => {
    try {
        const canvas = document.createElement('canvas');
        return !!(window.WebGLRenderingContext && (canvas.getContext('webgl') || canvas.getContext('webgl2')));
    } catch (err) {
        return false;
    }
})();

window.huFlower3D = { mount, supported, PRESETS, RENDER_MODES };
document.dispatchEvent(new CustomEvent('huFlower3DReady'));
