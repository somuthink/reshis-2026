import { rocket } from 'https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.5/bundles/datastar-rocket.js';

let echarts;

rocket('demand-chart', {
  props: ({ json, number }) => ({ points: json, capacity: number }),
  renderOnPropChange: false,
  render: ({ html }) => html`
    <style>:host{display:block;min-width:0}#plot{width:100%;height:280px}</style>
    <div id="plot" data-ref:plot>Загрузка графика…</div>`,
  onFirstRender: async ({ refs, props, observeProps, cleanup }) => {
    let chart, observer, removed = false;
    cleanup(() => {
      removed = true;
      observer?.disconnect();
      chart?.dispose();
    });
    try {
      const library = await (echarts ??= import('https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.esm.min.js'));
      if (removed) return;
      refs.plot.textContent = '';
      chart = library.init(refs.plot, null, { renderer: 'svg' });
      const update = () => {
        const { points, capacity } = props;
        chart.setOption({
          animation: false,
          textStyle: { fontFamily: 'system-ui, sans-serif', color: '#475569' },
          grid: { left: 45, right: 22, top: 30, bottom: 48 },
          tooltip: { trigger: 'item', formatter: '{b}' },
          xAxis: {
            type: 'value', name: 'Спрос, чел.', nameLocation: 'middle', nameGap: 30,
            min: Math.floor(Math.min(0, ...points.map(p => p.demand))),
            max: Math.ceil(Math.max(capacity, ...points.map(p => p.demand))),
            splitNumber: 3, axisLabel: { hideOverlap: true },
            splitLine: { lineStyle: { color: '#e0edf5' } }
          },
          yAxis: {
            type: 'value', min: 0, max: 100, interval: 20,
            axisLabel: { formatter: '{value}%' },
            splitLine: { lineStyle: { color: '#e0edf5' } }
          },
          series: [{
            type: 'scatter',
            data: points.map(p => ({
              value: [p.demand, Math.round(p.quantile * 100)],
              name: `${Math.round(p.quantile * 100)}%: ${p.demand.toFixed(2)} чел.`,
              symbolSize: p.quantile === 0.5 ? 14 : 9,
              itemStyle: { color: p.quantile === 0.5 ? '#b45309' : '#0284c7', opacity: 1 }
            })),
            markLine: {
              silent: true, symbol: 'none',
              lineStyle: { color: '#64748b', type: 'dashed' },
              label: { formatter: `Мест: ${capacity}`, position: 'insideEndTop' },
              data: [{ xAxis: capacity }]
            }
          }]
        }, { notMerge: true });
      };
      update();
      observeProps(update, 'points', 'capacity');
      observer = new ResizeObserver(() => chart.resize());
      observer.observe(refs.plot);
    } catch (error) {
      if (!removed) refs.plot.textContent = 'Не удалось загрузить график.';
      console.error(error);
    }
  }
});
