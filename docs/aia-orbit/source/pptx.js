const pptxgen = require('pptxgenjs');
const D = process.argv[2];
const p = new pptxgen(); p.layout = 'LAYOUT_WIDE'; p.title = 'AIA Orbit: one-slide EXCO view';
const s = p.addSlide(); s.addImage({ path: D + '/EXCO-One-Slide-AIA-Orbit.png', x: 0, y: 0, w: 13.333, h: 7.5, altText: 'AIA Orbit one-slide EXCO view' });
p.writeFile({ fileName: D + '/EXCO-One-Slide-AIA-Orbit.pptx' }).then(() => console.log('pptx ok'));
