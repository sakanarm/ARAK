// Images reach the bundle through Vite's asset pipeline as a URL string. Jest
// has no such pipeline and would try to parse the PNG as JavaScript, so a
// component that renders the logo takes its whole suite down with it — which is
// what happened to LoginPage's tests when the sign-in screen gained the lockup.
module.exports = 'test-file-stub';
