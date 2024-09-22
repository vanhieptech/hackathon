// var stompClient = null;
//
// function connect() {
//     var socket = new SockJS('/ws');
//     stompClient = Stomp.over(socket);
//     stompClient.connect({}, function (frame) {
//         console.log('Connected: ' + frame);
//         stompClient.subscribe('/topic/progress', function (message) {
//             showProgress(message.body);
//         });
//     }, function (error) {
//         console.log('STOMP error ' + error);
//         setTimeout(connect, 5000);
//     });
// }
//
// function showProgress(message) {
//     $("#progress").append("<p>" + message + "</p>");
// }
//
// $(document).ready(function () {
//     connect();
// });
//
// // Reconnect if the connection is lost
// $(window).on('focus', function () {
//     if (stompClient === null || !stompClient.connected) {
//         connect();
//     }
// });