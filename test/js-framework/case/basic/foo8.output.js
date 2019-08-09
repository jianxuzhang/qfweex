{
  type: 'container',
  children: [{
    type: 'container',
    style: {
      flexDirection: 'row'
    },
    event: ['click'],
    children: [{
      type: 'image',
      attr: {
        src: 'https://gd2.alicdn.com/bao/uploaded/i2/T14H1LFwBcXXXXXXXX_!!0-item_pic.jpg'
      },
      style: {
        width: 200,
        height: 200
      }
    }, {
      type: 'text',
      attr: {
        value: 'title1'
      },
      style: {
        flex: 1,
        color: '#ff0000',
        fontSize: 48,
        fontWeight: 'bold',
        backgroundColor: '#eeeeee'
      }
    }]
  }, {
    type: 'container',
    style: {
      flexDirection: 'row'
    },
    event: ['click'],
    children: [{
      type: 'image',
      attr: {
        src: 'https://gd1.alicdn.com/bao/uploaded/i1/TB1PXJCJFXXXXciXFXXXXXXXXXX_!!0-item_pic.jpg'
      },
      style: {
        width: 200,
        height: 200
      }
    }, {
      type: 'text',
      attr: {
        value: 'title2'
      },
      style: {
        flex: 1,
        color: '#ff0000',
        fontSize: 48,
        fontWeight: 'bold',
        backgroundColor: '#eeeeee'
      }
    }]
  }]
}