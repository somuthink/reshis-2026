import { rocket } from 'https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.5/bundles/datastar-rocket.js';

let echarts;
const number = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 });

rocket('exceedance-chart', {
  props: ({ json, number }) => ({ points: json, capacity: number, domain: json }),
  renderOnPropChange: false,
  render: ({ html }) => html`
    <style>
      :host{display:block;min-width:0}#plot{width:100%;height:290px}
      #plot:focus-visible{outline:2px solid var(--accent);outline-offset:2px;border-radius:6px}
    </style>
    <div id="plot" tabindex="0" data-ref:plot aria-label="Вероятность превышения спроса. Стрелки выбирают точку, Escape закрывает подсказку.">Загрузка графика…</div>`,
  onFirstRender: async ({ refs, props, observeProps, cleanup, host }) => {
    let chart, observer, removed = false, selected = 4;
    const onKey = event => {
      if (!chart) return;
      if (event.key === 'Escape') {
        chart.dispatchAction({ type: 'hideTip' });
        return;
      }
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      selected = event.key === 'Home' ? 0 : event.key === 'End' ? props.points.length - 1
        : Math.max(0, Math.min(props.points.length - 1, selected + (event.key === 'ArrowRight' ? 1 : -1)));
      chart.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: selected });
    };
    cleanup(() => {
      removed = true;
      observer?.disconnect();
      refs.plot.removeEventListener('keydown', onKey);
      chart?.dispose();
    });
    try {
      const library = await (echarts ??= import('https://cdn.jsdelivr.net/npm/echarts@5.5.0/dist/echarts.esm.min.js'));
      if (removed) return;
      refs.plot.textContent = '';
      chart = library.init(refs.plot, null, { renderer: 'svg' });
      const update = () => {
        const { points, capacity, domain } = props;
        const style = getComputedStyle(host);
        const color = token => style.getPropertyValue(token).trim();
        chart.setOption({
          animation: false,
          textStyle: { fontFamily: 'system-ui, sans-serif', color: color('--fg'), fontSize: 14 },
          grid: { left: '15%', right: '15%', top: 30, bottom: 50 },
          tooltip: {
            trigger: 'item', triggerOn: 'mousemove|click', confine: true, renderMode: 'richText',
            formatter: ({ data }) => `P${Math.round(data.quantile * 100)} · ${number.format(data.value[0])} чел.\n${data.value[1]}% — спрос выше`
          },
          xAxis: {
            type: 'value', min: domain[0], max: domain[1], splitNumber: 3,
            name: 'Спрос, чел.', nameLocation: 'middle', nameGap: 32,
            axisLabel: { fontSize: 14, hideOverlap: true, showMinLabel: false, showMaxLabel: false, formatter: value => number.format(value) },
            splitLine: { show: false }, axisLine: { show: false }, axisTick: { show: false }
          },
          yAxis: {
            type: 'value', min: 0, max: 100, interval: 50,
            axisLabel: { fontSize: 14, formatter: '{value}%' },
            splitLine: { lineStyle: { color: color('--border') } }
          },
          series: [{
            type: 'scatter',
            data: points.map(point => ({
              quantile: point.quantile,
              value: [point.demand, Math.round(100 * (1 - point.quantile))],
              symbolSize: point.quantile === 0.5 ? 14 : 10,
              itemStyle: { color: color(point.quantile === 0.5 ? '--warning' : '--accent'), opacity: 1 }
            })),
            emphasis: { scale: 1.5 },
            markLine: {
              silent: true, symbol: 'none',
              lineStyle: { color: color('--fg'), type: 'dashed', width: 1.5 },
              label: { formatter: `${capacity} мест`, position: 'end', rotate: 0, align: 'right', fontSize: 14 },
              data: [{ xAxis: capacity }]
            }
          }]
        }, { notMerge: true });
      };
      update();
      observeProps(update, 'points', 'capacity', 'domain');
      observer = new ResizeObserver(() => chart.resize());
      observer.observe(refs.plot);
      refs.plot.addEventListener('keydown', onKey);
    } catch (error) {
      if (!removed) refs.plot.textContent = 'Не удалось загрузить график.';
      console.error(error);
    }
  }
});
